package com.abnamro.delivery.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Starts the application on a random port and calls the API over real HTTP with the real tariff. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class PricingApiIT {

    private static final String URL = "/api/v1/pricing/evaluations";
    private static final String REQUEST_ID = "7cb82db3-fe9a-49e1-a0f9-fdeae2455869";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void pricesTheAssignmentExample() throws Exception {
        ResponseEntity<String> response = post(request("1012 ab", "5611EM", 2750, "NEXT_DAY"), new HttpHeaders());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        // Amounts are serialised as plain decimals with two places.
        assertThat(response.getBody())
                .contains("\"price\":{\"amount\":8.95,\"currency\":\"EUR\"}")
                .contains("{\"type\":\"BASE_PRICE\",\"amount\":6.95}")
                .contains("{\"type\":\"WEIGHT_SURCHARGE\",\"amount\":1.00}")
                .contains("{\"type\":\"URBAN_AREA_SURCHARGE\",\"amount\":1.00}");

        JsonNode body = json(response);
        assertThat(body.get("requestId").asText()).isEqualTo(REQUEST_ID);
        assertThat(body.get("eligible").asBoolean()).isTrue();
        assertThat(body.get("evaluatedAt").asText()).endsWith("Z");
        assertThat(body.has("rejectionCode")).isFalse();
    }

    @Test
    void returnsBusinessRejectionAsHttp200() throws Exception {
        ResponseEntity<String> response = post(request("5611EM", "3011AB", 20001, "STANDARD"), new HttpHeaders());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json(response);
        assertThat(body.get("eligible").asBoolean()).isFalse();
        assertThat(body.get("rejectionCode").asText()).isEqualTo("WEIGHT_LIMIT_EXCEEDED");
        assertThat(body.get("message").asText()).isEqualTo("The maximum supported parcel weight is 20000 grams.");
        assertThat(body.has("price")).isFalse();
    }

    @Test
    void rejectsSameDayBetweenDifferentAreas() throws Exception {
        ResponseEntity<String> response = post(request("5611EM", "1012AB", 100, "SAME_DAY"), new HttpHeaders());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(response).get("rejectionCode").asText()).isEqualTo("SAME_DAY_NOT_SUPPORTED");
    }

    @Test
    void returnsProblemDetailsForInvalidInputWithoutEchoingIt(CapturedOutput output) throws Exception {
        ResponseEntity<String> response = post(request("ABCDEF", "5611EM", 0, "NEXT_DAY"), new HttpHeaders());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        JsonNode body = json(response);
        assertThat(body.get("status").asInt()).isEqualTo(400);
        assertThat(body.get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(body.get("violations")).extracting(violation -> violation.get("field").asText())
                .containsExactlyInAnyOrder("originPostalCode", "weightInGrams");
        assertThat(response.getBody()).doesNotContain("ABCDEF");
        assertThat(output).contains("event=\"pricing.request_invalid\" reason=\"invalid_body\"");
        assertThat(output.getOut()).doesNotContain("ABCDEF");
    }

    @Test
    void rejectsFractionalWeight() throws Exception {
        ResponseEntity<String> response = post("""
                {"requestId":"%s","originPostalCode":"1012AB","destinationPostalCode":"5611EM",
                 "weightInGrams":2750.5,"deliveryType":"NEXT_DAY"}""".formatted(REQUEST_ID), new HttpHeaders());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).get("code").asText()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void rejectsMissingField() throws Exception {
        String body = """
                {"requestId":"%s","originPostalCode":"1012AB","weightInGrams":2750,"deliveryType":"NEXT_DAY"}"""
                .formatted(REQUEST_ID);

        ResponseEntity<String> response = post(body, new HttpHeaders());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).get("violations").get(0).get("field").asText()).isEqualTo("destinationPostalCode");
    }

    @Test
    void rejectsUnknownDeliveryTypeAndMalformedJson() throws Exception {
        ResponseEntity<String> unknownType = post(request("1012AB", "5611EM", 2750, "EXPRESS"), new HttpHeaders());
        ResponseEntity<String> malformed = post("{not json", new HttpHeaders());

        assertThat(unknownType.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(unknownType).get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(unknownType.getBody()).doesNotContain("EXPRESS");
        assertThat(malformed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void returnsTheCallersCorrelationIdAndGeneratesOneWhenMissing() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Correlation-ID", "order-123");

        ResponseEntity<String> withId = post(request("1012AB", "5611EM", 2750, "NEXT_DAY"), headers);
        ResponseEntity<String> withoutId = post(request("1012AB", "5611EM", 2750, "NEXT_DAY"), new HttpHeaders());

        assertThat(withId.getHeaders().getFirst("X-Correlation-ID")).isEqualTo("order-123");
        assertThat(withoutId.getHeaders().getFirst("X-Correlation-ID")).isNotBlank();
    }

    @Test
    void includesCorrelationIdInProblemDetails() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Correlation-ID", "order-456");

        ResponseEntity<String> response = post(request("1012AB", "5611EM", 0, "NEXT_DAY"), headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).get("correlationId").asText()).isEqualTo("order-456");
    }

    @Test
    void rejectsTooLongCorrelationId() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Correlation-ID", "x".repeat(129));

        ResponseEntity<String> response = post(request("1012AB", "5611EM", 2750, "NEXT_DAY"), headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).get("violations").get(0).get("field").asText()).isEqualTo("X-Correlation-ID");
    }

    @ParameterizedTest
    @ValueSource(strings = {"order 123", " "})
    void rejectsCorrelationIdsWithInvalidCharactersOrBlankValues(String correlationId) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Correlation-ID", correlationId);

        ResponseEntity<String> response = post(request("1012AB", "5611EM", 2750, "NEXT_DAY"), headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).get("violations").get(0).get("field").asText()).isEqualTo("X-Correlation-ID");
        assertThat(response.getHeaders().getFirst("X-Correlation-ID")).isNotEqualTo(correlationId).isNotBlank();
    }

    @Test
    void keepsStatusForUnsupportedMethod() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(URL, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getHeaders().getAllow()).containsExactly(org.springframework.http.HttpMethod.POST);
        assertThat(json(response).get("code").asText()).isEqualTo("REQUEST_NOT_SUPPORTED");
    }

    private ResponseEntity<String> post(String body, HttpHeaders headers) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity(URL, new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }

    private static String request(String origin, String destination, long weight, String deliveryType) {
        return """
                {"requestId":"%s","originPostalCode":"%s","destinationPostalCode":"%s",
                 "weightInGrams":%d,"deliveryType":"%s"}""".formatted(REQUEST_ID, origin, destination, weight, deliveryType);
    }
}
