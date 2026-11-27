package com.abnamro.delivery.order.pricing;

import static com.abnamro.delivery.order.fixture.TestOrders.orderRequest;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.abnamro.delivery.order.configuration.CorrelationIdFilter;
import com.abnamro.delivery.order.configuration.PricingClientConfiguration;
import com.abnamro.delivery.order.configuration.PricingClientProperties;
import com.abnamro.delivery.order.exception.PricingUnavailableException;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Real HTTP calls to a WireMock Pricing with the real RestClient, timeouts, retry and circuit breaker.
 * No database: only the Pricing client beans are started.
 */
@SpringBootTest(
        classes = {PricingClient.class, PricingClientConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "delivery.pricing.circuit-breaker.sliding-window-size=4",
                "delivery.pricing.circuit-breaker.minimum-number-of-calls=4",
                "delivery.pricing.circuit-breaker.wait-duration-in-open-state=60s"})
@EnableConfigurationProperties(PricingClientProperties.class)
@ImportAutoConfiguration({JacksonAutoConfiguration.class, RestClientAutoConfiguration.class})
class PricingClientIT {

    private static final String PATH = "/api/v1/pricing/evaluations";

    @RegisterExtension
    static WireMockExtension pricing = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void pricingUrl(DynamicPropertyRegistry registry) {
        registry.add("delivery.pricing.base-url", pricing::baseUrl);
    }

    @Autowired
    private PricingClient pricingClient;

    @Autowired
    private CircuitBreaker pricingCircuitBreaker;

    private final UUID requestId = UUID.randomUUID();

    @BeforeEach
    void resetCircuit() {
        pricingCircuitBreaker.reset();
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void returnsTheEligiblePriceAndForwardsTheCorrelationId() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "test-correlation-id");
        pricing.stubFor(post(PATH).willReturn(okJson(eligible(requestId, "8.95"))));

        PricingDecision decision = pricingClient.evaluate(requestId, orderRequest());

        assertThat(decision.isEligible()).isTrue();
        assertThat(decision.price().amount()).isEqualByComparingTo(new BigDecimal("8.95"));
        assertThat(decision.price().currency()).isEqualTo("EUR");
        pricing.verify(postRequestedFor(urlEqualTo(PATH))
                .withHeader(CorrelationIdFilter.HEADER, equalTo("test-correlation-id"))
                .withRequestBody(matchingJsonPath("$.originPostalCode", equalTo("1012AB")))
                .withRequestBody(matchingJsonPath("$.weightInGrams", equalTo("2750"))));
    }

    @Test
    void returnsABusinessRejection() {
        pricing.stubFor(post(PATH).willReturn(okJson("""
                {"requestId":"%s","eligible":false,"rejectionCode":"WEIGHT_LIMIT_EXCEEDED",
                 "message":"Too heavy","evaluatedAt":"2026-11-25T08:30:00Z"}""".formatted(requestId))));

        PricingDecision decision = pricingClient.evaluate(requestId, orderRequest());

        assertThat(decision.isEligible()).isFalse();
        assertThat(decision.rejectionCode()).isEqualTo("WEIGHT_LIMIT_EXCEEDED");
    }

    @Test
    void retriesA503OnceThenReportsUnavailable() {
        pricing.stubFor(post(PATH).willReturn(aResponse().withStatus(503)));

        assertUnavailable("server_error");
        pricing.verify(2, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void doesNotRetryA500() {
        pricing.stubFor(post(PATH).willReturn(serverError()));

        assertUnavailable("server_error");
        pricing.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void treatsASlowResponseAsATimeoutWithoutRetrying() {
        pricing.stubFor(post(PATH).willReturn(okJson(eligible(requestId, "8.95")).withFixedDelay(2500)));

        assertUnavailable("timeout");
        pricing.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void treatsAMismatchingRequestIdAsInvalid() {
        pricing.stubFor(post(PATH).willReturn(okJson(eligible(UUID.randomUUID(), "8.95"))));

        assertUnavailable("invalid_response");
    }

    @Test
    void treatsAnInvalidTotalAsInvalid() {
        pricing.stubFor(post(PATH).willReturn(okJson(eligible(requestId, "8.955"))));

        assertUnavailable("invalid_response");
    }

    @Test
    void treatsATotalTooLargeToStoreAsInvalid() {
        pricing.stubFor(post(PATH).willReturn(okJson(eligible(requestId, "100000000.00"))));

        assertUnavailable("invalid_response");
    }

    @Test
    void acceptsTheLargestTotalThatCanBeStored() {
        pricing.stubFor(post(PATH).willReturn(okJson(eligible(requestId, "99999999.99"))));

        assertThat(pricingClient.evaluate(requestId, orderRequest()).price().amount())
                .isEqualByComparingTo("99999999.99");
    }

    @Test
    void treatsARejectionCodeTooLongToStoreAsInvalid() {
        pricing.stubFor(post(PATH).willReturn(okJson("""
                {"requestId":"%s","eligible":false,"rejectionCode":"%s",
                 "message":"No","evaluatedAt":"2026-11-25T08:30:00Z"}""".formatted(requestId, "X".repeat(51)))));

        assertUnavailable("invalid_response");
    }

    @Test
    void treatsAnUnreadableBodyAsInvalid() {
        pricing.stubFor(post(PATH).willReturn(okJson("not json")));

        assertUnavailable("invalid_response");
    }

    @Test
    void treatsA400AsOurOwnBug() {
        pricing.stubFor(post(PATH).willReturn(aResponse().withStatus(400)));

        assertThatThrownBy(() -> pricingClient.evaluate(requestId, orderRequest()))
                .isInstanceOf(IllegalStateException.class);
        pricing.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void opensTheCircuitAfterRepeatedFailuresAndStopsCallingPricing() {
        pricing.stubFor(post(PATH).willReturn(serverError()));
        for (int i = 0; i < 4; i++) {
            assertUnavailable("server_error");
        }

        assertUnavailable("circuit_open");
        pricing.verify(4, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void businessRejectionsDoNotOpenTheCircuit() {
        pricing.stubFor(post(PATH).willReturn(okJson("""
                {"requestId":"%s","eligible":false,"rejectionCode":"SAME_DAY_NOT_SUPPORTED",
                 "message":"No","evaluatedAt":"2026-11-25T08:30:00Z"}""".formatted(requestId))));
        for (int i = 0; i < 6; i++) {
            pricingClient.evaluate(requestId, orderRequest());
        }

        assertThat(pricingCircuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    private void assertUnavailable(String reason) {
        assertThatThrownBy(() -> pricingClient.evaluate(requestId, orderRequest()))
                .isInstanceOf(PricingUnavailableException.class)
                .extracting("reason").isEqualTo(reason);
    }

    private static String eligible(UUID requestId, String amount) {
        return """
                {"requestId":"%s","eligible":true,"price":{"amount":%s,"currency":"EUR"},
                 "priceComponents":[{"type":"BASE_PRICE","amount":%s}],"evaluatedAt":"2026-11-25T08:30:00Z"}
                """.formatted(requestId, amount, amount);
    }
}
