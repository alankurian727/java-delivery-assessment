package com.abnamro.delivery.order.pricing;

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
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import java.math.BigDecimal;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Calls the Pricing & Eligibility API through a circuit breaker wrapping a retry. Any answer that cannot be trusted
 * (unreachable, timed out, circuit open, server error or invalid body) becomes {@link PricingUnavailableException},
 * so an order is never confirmed without a valid price.
 */
@Component
public class PricingClient {

    private static final Logger log = LoggerFactory.getLogger(PricingClient.class);
    private static final String EVALUATIONS_PATH = "/api/v1/pricing/evaluations";

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final ObjectMapper objectMapper;

    public PricingClient(RestClient pricingRestClient, CircuitBreaker pricingCircuitBreaker, Retry pricingRetry,
                         ObjectMapper objectMapper) {
        this.restClient = pricingRestClient;
        this.circuitBreaker = pricingCircuitBreaker;
        this.retry = pricingRetry;
        this.objectMapper = objectMapper;
    }

    public PricingDecision evaluate(UUID pricingRequestId, OrderRequest order) {
        PricingEvaluationRequest request = new PricingEvaluationRequest(
                pricingRequestId,
                order.sender().postalCode().value(),
                order.recipient().postalCode().value(),
                order.weightInGrams(),
                DeliveryType.valueOf(order.deliveryType().name()));

        // Validation runs inside the decorated call, so an invalid response counts as a circuit-breaker failure.
        Supplier<PricingDecision> call = () -> toDecision(pricingRequestId, post(request));
        call = Retry.decorateSupplier(retry, call);
        call = CircuitBreaker.decorateSupplier(circuitBreaker, call);

        try {
            return call.get();
        } catch (CallNotPermittedException e) {
            throw unavailable("circuit_open");
        } catch (HttpClientErrorException e) {
            // A 4xx means Delivery Order sent an invalid request: a bug, not an outage. Never retried.
            throw new IllegalStateException("Pricing rejected the request with HTTP " + e.getStatusCode().value());
        } catch (HttpServerErrorException e) {
            throw unavailable("server_error");
        } catch (ResourceAccessException e) {
            boolean readTimeout = e.getCause() instanceof HttpTimeoutException
                    && !(e.getCause() instanceof HttpConnectTimeoutException);
            throw unavailable(readTimeout ? "timeout" : "connection_failed");
        } catch (RestClientException e) {
            throw unavailable("invalid_response");
        }
    }

    private JsonNode post(PricingEvaluationRequest request) {
        return restClient.post()
                .uri(EVALUATIONS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(JsonNode.class);
    }

    private PricingDecision toDecision(UUID pricingRequestId, JsonNode response) {
        if (response == null || !response.path("eligible").isBoolean()
                || !pricingRequestId.toString().equals(response.path("requestId").asText())) {
            throw unavailable("invalid_response");
        }
        try {
            if (response.get("eligible").asBoolean()) {
                EligibleEvaluation eligible = objectMapper.treeToValue(response, EligibleEvaluation.class);
                return PricingDecision.eligible(pricingRequestId, validPrice(eligible));
            }
            RejectedEvaluation rejected = objectMapper.treeToValue(response, RejectedEvaluation.class);
            if (rejected.getRejectionCode() == null || rejected.getRejectionCode().isBlank()) {
                throw unavailable("invalid_response");
            }
            return PricingDecision.rejected(pricingRequestId, rejected.getRejectionCode());
        } catch (JsonProcessingException e) {
            throw unavailable("invalid_response");
        }
    }

    /**
     * Accepts only a EUR price that equals the sum of its components. The total and every component amount must be
     * present, non-negative and have at most two decimals.
     */
    private Money validPrice(EligibleEvaluation eligible) {
        if (eligible.getPrice() == null || eligible.getPrice().getCurrency() == null
                || !"EUR".equals(eligible.getPrice().getCurrency().getValue())
                || !isValidAmount(eligible.getPrice().getAmount())
                || eligible.getPriceComponents() == null || eligible.getPriceComponents().isEmpty()) {
            throw unavailable("invalid_response");
        }
        BigDecimal componentSum = BigDecimal.ZERO;
        for (PriceComponent component : eligible.getPriceComponents()) {
            if (component == null || !isValidAmount(component.getAmount())) {
                throw unavailable("invalid_response");
            }
            componentSum = componentSum.add(component.getAmount());
        }
        BigDecimal total = eligible.getPrice().getAmount();
        if (total.compareTo(componentSum) != 0) {
            throw unavailable("invalid_response");
        }
        return new Money(total.setScale(2), "EUR");
    }

    private static boolean isValidAmount(BigDecimal amount) {
        return amount != null && amount.signum() >= 0 && amount.stripTrailingZeros().scale() <= 2;
    }

    private static PricingUnavailableException unavailable(String reason) {
        log.atWarn().setMessage("Pricing unavailable")
                .addKeyValue("event", "pricing.call_failed")
                .addKeyValue("reason", reason)
                .log();
        return new PricingUnavailableException(reason);
    }
}
