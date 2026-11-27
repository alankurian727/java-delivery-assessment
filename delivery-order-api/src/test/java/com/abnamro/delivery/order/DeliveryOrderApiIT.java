package com.abnamro.delivery.order;

import static com.abnamro.delivery.order.fixture.TestOrders.requestJson;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** The Delivery Order API over real HTTP, with PostgreSQL and a WireMock Pricing. */
@ExtendWith(OutputCaptureExtension.class)
class DeliveryOrderApiIT extends IntegrationTest {

    private static final String ORDERS = "/api/v1/delivery-orders";
    private static final String PRICING_PATH = "/api/v1/pricing/evaluations";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void createsAnOrderAndReturnsItOnGet(CapturedOutput output) throws Exception {
        pricingAnswers(eligible("8.95"));

        ResponseEntity<String> created = create("key-1", requestJson());

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode order = json(created);
        String orderId = order.get("orderId").asText();
        assertThat(created.getHeaders().getLocation()).hasToString(ORDERS + "/" + orderId);
        assertThat(order.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(created.getBody()).contains("\"price\":{\"amount\":8.95,\"currency\":\"EUR\"}");
        // Stored in UTC, shown with the Europe/Amsterdam offset (+01:00 in November).
        assertThat(order.get("createdAt").asText()).endsWith("+01:00");

        ResponseEntity<String> fetched = restTemplate.getForEntity(ORDERS + "/" + orderId, String.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(fetched)).isEqualTo(order);

        assertThat(count("delivery_order")).isEqualTo(1);
        assertThat(output).doesNotContain("Test Sender").doesNotContain("Test Recipient");
    }

    @Test
    void returnsTheSameOrderForAnEquivalentRequestWithoutCallingPricingAgain() throws Exception {
        pricingAnswers(eligible("8.95"));
        String first = json(create("key-1", requestJson("1012AB", 2750, "NL"))).get("orderId").asText();

        ResponseEntity<String> repeated = create("key-1", requestJson("1012 ab", 2750, "NL"));

        assertThat(repeated.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(json(repeated).get("orderId").asText()).isEqualTo(first);
        PRICING.verify(1, postRequestedFor(urlEqualTo(PRICING_PATH)));
        assertThat(count("delivery_order")).isEqualTo(1);
    }

    @Test
    void rejectsTheSameKeyWithDifferentContent() throws Exception {
        pricingAnswers(eligible("8.95"));
        create("key-1", requestJson("1012AB", 2750, "NL"));

        ResponseEntity<String> conflict = create("key-1", requestJson("1012AB", 3000, "NL"));

        assertProblem(conflict, HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT");
        assertThat(count("delivery_order")).isEqualTo(1);
    }

    @Test
    void returnsTheSameBusinessRejectionAgainWithoutCreatingAnOrder() throws Exception {
        pricingAnswers(okJson("""
                {"requestId":"{{jsonPath request.body '$.requestId'}}","eligible":false,
                 "rejectionCode":"WEIGHT_LIMIT_EXCEEDED","message":"Too heavy","evaluatedAt":"2026-11-25T08:30:00Z"}
                """).withTransformers("response-template"));

        ResponseEntity<String> rejected = create("key-1", requestJson("1012AB", 25000, "NL"));
        ResponseEntity<String> repeated = create("key-1", requestJson("1012AB", 25000, "NL"));

        for (ResponseEntity<String> response : List.of(rejected, repeated)) {
            assertProblem(response, HttpStatus.UNPROCESSABLE_ENTITY, "WEIGHT_LIMIT_EXCEEDED");
            assertThat(json(response).get("orderStatus").asText()).isEqualTo("REJECTED");
        }
        PRICING.verify(1, postRequestedFor(urlEqualTo(PRICING_PATH)));
        assertThat(count("delivery_order")).isZero();
        assertThat(count("idempotency_record")).isEqualTo(1);
    }

    @Test
    void rejectsAnUnsupportedCountryWithoutCallingPricing() throws Exception {
        ResponseEntity<String> response = create("key-1", requestJson("1012AB", 2750, "BE"));

        assertProblem(response, HttpStatus.UNPROCESSABLE_ENTITY, "UNSUPPORTED_COUNTRY");
        PRICING.verify(0, postRequestedFor(urlEqualTo(PRICING_PATH)));
    }

    @Test
    void neverConfirmsWhenPricingIsDownAndAcceptsTheRetryLater() throws Exception {
        pricingAnswers(aResponse().withStatus(503));

        ResponseEntity<String> unavailable = create("key-1", requestJson());

        assertProblem(unavailable, HttpStatus.SERVICE_UNAVAILABLE, "PRICING_UNAVAILABLE");
        assertThat(unavailable.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
        assertThat(json(unavailable).get("orderStatus").asText()).isEqualTo("PRICING_UNAVAILABLE");
        assertThat(count("delivery_order")).isZero();
        assertThat(count("idempotency_record")).isZero();

        PRICING.resetAll();
        pricingAnswers(eligible("8.95"));
        assertThat(create("key-1", requestJson()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void keepsTheConfirmedPriceWhenPricingChangesLater() throws Exception {
        pricingAnswers(eligible("8.95"));
        String orderId = json(create("key-1", requestJson())).get("orderId").asText();

        PRICING.resetAll();
        pricingAnswers(eligible("12.50"));

        JsonNode stored = json(restTemplate.getForEntity(ORDERS + "/" + orderId, String.class));
        assertThat(stored.get("price").get("amount").decimalValue()).isEqualByComparingTo("8.95");
    }

    @Test
    void createsOneOrderForConcurrentRequestsWithTheSameKey() throws Exception {
        pricingAnswers(eligible("8.95"));
        Callable<ResponseEntity<String>> request = () -> create("same-key", requestJson());

        List<ResponseEntity<String>> responses;
        try (ExecutorService executor = Executors.newFixedThreadPool(10)) {
            List<Future<ResponseEntity<String>>> futures =
                    executor.invokeAll(IntStream.range(0, 10).mapToObj(i -> request).toList());
            responses = futures.stream().map(DeliveryOrderApiIT::resultOf).toList();
        }

        assertThat(responses).allSatisfy(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED));
        assertThat(responses.stream().map(this::orderIdOf).distinct()).hasSize(1);
        assertThat(count("delivery_order")).isEqualTo(1);
        assertThat(count("idempotency_record")).isEqualTo(1);
    }

    @Test
    void rejectsInvalidRequestsWithoutCallingPricing() throws Exception {
        assertProblem(create("key-1", requestJson("1012A", 2750, "NL")), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertProblem(create("key-2", requestJson("1012AB", 0, "NL")), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertProblem(create(null, requestJson()), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        PRICING.verify(0, postRequestedFor(urlEqualTo(PRICING_PATH)));
    }

    @Test
    void namesTheInvalidHeaderInTheViolations() throws Exception {
        ResponseEntity<String> invalidKey = create("my key", requestJson());

        HttpHeaders headers = jsonHeaders();
        headers.set("Idempotency-Key", "key-1");
        headers.set("X-Correlation-ID", "x".repeat(129));
        ResponseEntity<String> invalidCorrelationId =
                restTemplate.postForEntity(ORDERS, new HttpEntity<>(requestJson(), headers), String.class);

        assertProblem(invalidKey, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertThat(json(invalidKey).get("violations").get(0).get("field").asText()).isEqualTo("Idempotency-Key");
        assertProblem(invalidCorrelationId, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertThat(json(invalidCorrelationId).get("violations").get(0).get("field").asText())
                .isEqualTo("X-Correlation-ID");
        PRICING.verify(0, postRequestedFor(urlEqualTo(PRICING_PATH)));
    }

    @Test
    void rejectsABodyThatIsNotValidJson() throws Exception {
        assertProblem(create("key-1", "{not json"), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    }

    @Test
    void keepsSpringsStatusAndHeadersForAnUnsupportedMethod() throws Exception {
        ResponseEntity<String> response = restTemplate.exchange(ORDERS, HttpMethod.PUT,
                new HttpEntity<>(requestJson(), jsonHeaders()), String.class);

        assertProblem(response, HttpStatus.METHOD_NOT_ALLOWED, "REQUEST_NOT_SUPPORTED");
        assertThat(response.getHeaders().getAllow()).contains(HttpMethod.POST);
    }

    @Test
    void returns404ForAnUnknownOrderAnd400ForAnInvalidId() throws Exception {
        assertProblem(restTemplate.getForEntity(ORDERS + "/9f3c2b1e-0000-4000-8000-000000000000", String.class),
                HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND");
        assertProblem(restTemplate.getForEntity(ORDERS + "/not-a-uuid", String.class),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<String> create(String idempotencyKey, String body) {
        HttpHeaders headers = jsonHeaders();
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return restTemplate.postForEntity(ORDERS, new HttpEntity<>(body, headers), String.class);
    }

    private void assertProblem(ResponseEntity<String> response, HttpStatus status, String code) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(json(response).get("code").asText()).isEqualTo(code);
    }

    /** Echoes the requestId Delivery Order sent, as the real Pricing does. */
    private static ResponseDefinitionBuilder eligible(String amount) {
        return okJson("""
                {"requestId":"{{jsonPath request.body '$.requestId'}}","eligible":true,
                 "price":{"amount":%s,"currency":"EUR"},"priceComponents":[{"type":"BASE_PRICE","amount":%s}],
                 "evaluatedAt":"2026-11-25T08:30:00Z"}
                """.formatted(amount, amount)).withTransformers("response-template");
    }

    private static void pricingAnswers(ResponseDefinitionBuilder response) {
        PRICING.stubFor(post(PRICING_PATH).willReturn(response));
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }

    private String orderIdOf(ResponseEntity<String> response) {
        try {
            return json(response).get("orderId").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ResponseEntity<String> resultOf(Future<ResponseEntity<String>> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
