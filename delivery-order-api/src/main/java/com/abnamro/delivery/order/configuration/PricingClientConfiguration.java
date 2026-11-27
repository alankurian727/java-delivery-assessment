package com.abnamro.delivery.order.configuration;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Configuration
public class PricingClientConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PricingClientConfiguration.class);

    @Bean
    RestClient pricingRestClient(RestClient.Builder builder, PricingClientProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        return builder
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                // Forward the caller's correlation ID so both services log the same ID for one order.
                .requestInterceptor((request, body, execution) -> {
                    String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
                    if (correlationId != null) {
                        request.getHeaders().set(CorrelationIdFilter.HEADER, correlationId);
                    }
                    return execution.execute(request, body);
                })
                .build();
    }

    /** Caps concurrent Pricing calls per instance; when full, requests fail fast instead of queueing (ADR-007). */
    @Bean
    Bulkhead pricingBulkhead(PricingClientProperties properties) {
        return Bulkhead.of("pricing", BulkheadConfig.custom()
                .maxConcurrentCalls(properties.maxConcurrentCalls())
                .maxWaitDuration(Duration.ZERO)
                .build());
    }

    /**
     * Stops calling Pricing for a while when most recent calls failed, so clients get a 503 at once instead of
     * after a timeout, and Pricing gets room to recover. Business rejections are normal answers and 4xx responses
     * are our own bugs, so neither counts as a failure.
     */
    @Bean
    CircuitBreaker pricingCircuitBreaker(PricingClientProperties properties) {
        PricingClientProperties.CircuitBreakerSettings settings = properties.circuitBreaker();
        CircuitBreaker circuitBreaker = CircuitBreaker.of("pricing", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(settings.slidingWindowSize())
                .minimumNumberOfCalls(settings.minimumNumberOfCalls())
                .failureRateThreshold(settings.failureRateThreshold())
                .waitDurationInOpenState(settings.waitDurationInOpenState())
                .ignoreExceptions(HttpClientErrorException.class)
                .build());
        circuitBreaker.getEventPublisher().onStateTransition(event -> log.atWarn()
                .setMessage("Pricing circuit breaker changed state")
                .addKeyValue("event", "pricing.circuit_state_changed")
                .addKeyValue("from", event.getStateTransition().getFromState())
                .addKeyValue("to", event.getStateTransition().getToState())
                .log());
        return circuitBreaker;
    }

    /**
     * Retries only when the request never reached Pricing (connection refused or connect timeout) or Pricing
     * answered 502/503/504. A read timeout is not retried: Pricing may still be working on the request.
     */
    @Bean
    Retry pricingRetry(PricingClientProperties properties) {
        Retry retry = Retry.of("pricing", RetryConfig.custom()
                .maxAttempts(properties.maxAttempts())
                .intervalFunction(IntervalFunction.ofRandomized(Duration.ofMillis(150), 0.33)) // 100-200 ms
                .retryOnException(PricingClientConfiguration::isRetryable)
                .build());
        retry.getEventPublisher().onRetry(event -> log.atWarn()
                .setMessage("Retrying Pricing call")
                .addKeyValue("event", "pricing.call_retry")
                .addKeyValue("reason", event.getLastThrowable() instanceof HttpServerErrorException
                        ? "server_error" : "connection_failed")
                .addKeyValue("attempt", event.getNumberOfRetryAttempts())
                .log());
        return retry;
    }

    static boolean isRetryable(Throwable throwable) {
        if (throwable instanceof HttpServerErrorException e) {
            int status = e.getStatusCode().value();
            return status == 502 || status == 503 || status == 504;
        }
        return throwable instanceof ResourceAccessException e
                && (e.getCause() instanceof ConnectException || e.getCause() instanceof HttpConnectTimeoutException);
    }
}
