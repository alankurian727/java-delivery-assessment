package com.abnamro.delivery.order.pricing;

import com.abnamro.delivery.order.configuration.PricingClientProperties;
import com.abnamro.delivery.order.domain.Money;
import com.abnamro.delivery.order.domain.OrderRequest;
import com.abnamro.delivery.order.exception.PricingUnavailableException;
import com.abnamro.delivery.order.pricing.model.DeliveryType;
import com.abnamro.delivery.order.pricing.model.EligibleEvaluation;
import com.abnamro.delivery.order.pricing.model.PriceComponent;
import com.abnamro.delivery.order.pricing.model.PricingEvaluationRequest;
import com.abnamro.delivery.order.pricing.model.RejectedEvaluation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Calls the Pricing & Eligibility API. Any answer that cannot be trusted (unreachable, timed out, overloaded,
 * server error or invalid body) becomes {@link PricingUnavailableException}, so an order is never confirmed
 * without a valid price.
 */
@Component
public class PricingClient {

    private static final Logger log = LoggerFactory.getLogger(PricingClient.class);
    private static final String EVALUATIONS_PATH = "/api/v1/pricing/evaluations";

    private final RestClient restClient;
    private final Bulkhead bulkhead;
    private final ObjectMapper objectMapper;
    private final int maxAttempts;

    public PricingClient(RestClient pricingRestClient, Bulkhead pricingBulkhead, ObjectMapper objectMapper,
                         PricingClientProperties properties) {
        this.restClient = pricingRestClient;
        this.bulkhead = pricingBulkhead;
        this.objectMapper = objectMapper;
        this.maxAttempts = properties.maxAttempts();
    }

    public PricingDecision evaluate(UUID pricingRequestId, OrderRequest order) {
        PricingEvaluationRequest request = new PricingEvaluationRequest(
                pricingRequestId,
                order.sender().postalCode().value(),
                order.recipient().postalCode().value(),
                order.weightInGrams(),
                DeliveryType.valueOf(order.deliveryType().name()));
        try {
            JsonNode response = Bulkhead.decorateSupplier(bulkhead, () -> callWithRetry(request)).get();
            return toDecision(pricingRequestId, response);
        } catch (BulkheadFullException e) {
            throw unavailable("bulkhead_full", 0);
        }
    }

    private JsonNode callWithRetry(PricingEvaluationRequest request) {
        for (int attempt = 1; ; attempt++) {
            try {
                return restClient.post()
                        .uri(EVALUATIONS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .body(JsonNode.class);
            } catch (HttpClientErrorException e) {
                // A 4xx means Delivery Order sent an invalid request: a bug, not an outage. Never retried.
                throw new IllegalStateException("Pricing rejected the request with HTTP " + e.getStatusCode().value());
            } catch (HttpServerErrorException e) {
                if (attempt < maxAttempts && isRetryable(e.getStatusCode())) {
                    logRetry("server_error", attempt);
                    pause();
                    continue;
                }
                throw unavailable("server_error", attempt);
            } catch (ResourceAccessException e) {
                // Only retry when the request never reached Pricing; a read timeout may mean it is still working.
                boolean notSent = e.getCause() instanceof ConnectException || e.getCause() instanceof HttpConnectTimeoutException;
                if (attempt < maxAttempts && notSent) {
                    logRetry("connection_failed", attempt);
                    pause();
                    continue;
                }
                throw unavailable(e.getCause() instanceof HttpTimeoutException && !notSent ? "timeout" : "connection_failed", attempt);
            } catch (RestClientException e) {
                throw unavailable("invalid_response", attempt);
            }
        }
    }

    private PricingDecision toDecision(UUID pricingRequestId, JsonNode response) {
        if (response == null || !response.path("eligible").isBoolean()
                || !pricingRequestId.toString().equals(response.path("requestId").asText())) {
            throw unavailable("invalid_response", 0);
        }
        try {
            if (response.get("eligible").asBoolean()) {
                EligibleEvaluation eligible = objectMapper.treeToValue(response, EligibleEvaluation.class);
                return PricingDecision.eligible(pricingRequestId, validPrice(eligible));
            }
            RejectedEvaluation rejected = objectMapper.treeToValue(response, RejectedEvaluation.class);
            if (rejected.getRejectionCode() == null || rejected.getRejectionCode().isBlank()) {
                throw unavailable("invalid_response", 0);
            }
            return PricingDecision.rejected(pricingRequestId, rejected.getRejectionCode());
        } catch (JsonProcessingException e) {
            throw unavailable("invalid_response", 0);
        }
    }

    /** Accepts only a EUR price with at most two decimals that equals the sum of its components. */
    private Money validPrice(EligibleEvaluation eligible) {
        if (eligible.getPrice() == null || eligible.getPrice().getAmount() == null || eligible.getPrice().getCurrency() == null
                || eligible.getPriceComponents() == null || eligible.getPriceComponents().isEmpty()) {
            throw unavailable("invalid_response", 0);
        }
        BigDecimal amount = eligible.getPrice().getAmount();
        BigDecimal sum = eligible.getPriceComponents().stream()
                .map(PriceComponent::getAmount)
                .reduce(BigDecimal.ZERO, (a, b) -> b == null ? a : a.add(b));
        boolean valid = "EUR".equals(eligible.getPrice().getCurrency().getValue())
                && amount.signum() >= 0
                && amount.stripTrailingZeros().scale() <= 2
                && amount.compareTo(sum) == 0;
        if (!valid) {
            throw unavailable("invalid_response", 0);
        }
        return new Money(amount.setScale(2), "EUR");
    }

    private static boolean isRetryable(HttpStatusCode status) {
        int code = status.value();
        return code == 502 || code == 503 || code == 504;
    }

    private static void pause() {
        try {
            Thread.sleep(100 + ThreadLocalRandom.current().nextInt(100));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PricingUnavailableException("interrupted");
        }
    }

    private static void logRetry(String reason, int attempt) {
        log.atWarn().setMessage("Retrying Pricing call")
                .addKeyValue("event", "pricing.call_retry")
                .addKeyValue("reason", reason)
                .addKeyValue("attempt", attempt)
                .log();
    }

    private static PricingUnavailableException unavailable(String reason, int attempts) {
        log.atWarn().setMessage("Pricing unavailable")
                .addKeyValue("event", "pricing.call_failed")
                .addKeyValue("reason", reason)
                .addKeyValue("attempts", attempts)
                .log();
        return new PricingUnavailableException(reason);
    }
}
