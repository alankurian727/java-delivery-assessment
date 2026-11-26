package com.abnamro.delivery.pricing.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final AtomicReference<String> idDuringRequest = new AtomicReference<>();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void keepsAValidCallerIdForTheRequestAndTheResponse() throws Exception {
        filter.doFilter(request("order-123"), response, chainRecordingMdc());

        assertThat(idDuringRequest.get()).isEqualTo("order-123");
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("order-123");
    }

    @Test
    void generatesAnIdWhenTheHeaderIsMissing() throws Exception {
        filter.doFilter(request(null), response, chainRecordingMdc());

        assertThat(UUID.fromString(idDuringRequest.get())).isNotNull();
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo(idDuringRequest.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "order 123", "line\nbreak"})
    void replacesBlankOrInvalidIdsWithAGeneratedOne(String invalid) throws Exception {
        filter.doFilter(request(invalid), response, chainRecordingMdc());

        assertThat(UUID.fromString(idDuringRequest.get())).isNotNull();
    }

    @Test
    void replacesIdsLongerThan128Characters() throws Exception {
        filter.doFilter(request("x".repeat(129)), response, chainRecordingMdc());

        assertThat(UUID.fromString(idDuringRequest.get())).isNotNull();
    }

    @Test
    void removesTheIdFromTheMdcAfterTheRequest() throws Exception {
        filter.doFilter(request("order-123"), response, chainRecordingMdc());

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void removesTheIdFromTheMdcEvenWhenTheRequestFails() {
        MockFilterChain failingChain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response)
                    throws ServletException {
                throw new ServletException("boom");
            }
        };

        assertThatThrownBy(() -> filter.doFilter(request("order-123"), response, failingChain))
                .isInstanceOf(ServletException.class);
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    private static MockHttpServletRequest request(String correlationId) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/pricing/evaluations");
        if (correlationId != null) {
            request.addHeader(CorrelationIdFilter.HEADER, correlationId);
        }
        return request;
    }

    private MockFilterChain chainRecordingMdc() {
        return new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
                idDuringRequest.set(MDC.get(CorrelationIdFilter.MDC_KEY));
            }
        };
    }
}
