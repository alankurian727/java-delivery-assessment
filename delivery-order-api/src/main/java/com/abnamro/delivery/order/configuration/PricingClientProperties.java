package com.abnamro.delivery.order.configuration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Connection and resilience settings for the Pricing & Eligibility API (delivery.pricing in application.yml). */
@Validated
@ConfigurationProperties("delivery.pricing")
public record PricingClientProperties(
        @NotNull URI baseUrl,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        @Min(1) @Max(3) int maxAttempts,
        @Valid @NotNull CircuitBreakerSettings circuitBreaker) {

    /** Opens when failureRateThreshold % of the last slidingWindowSize calls failed (after minimumNumberOfCalls). */
    public record CircuitBreakerSettings(
            @Min(1) @Max(100) int failureRateThreshold,
            @Min(1) int slidingWindowSize,
            @Min(1) int minimumNumberOfCalls,
            @NotNull Duration waitDurationInOpenState) {
    }
}
