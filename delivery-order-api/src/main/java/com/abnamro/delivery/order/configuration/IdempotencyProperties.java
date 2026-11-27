package com.abnamro.delivery.order.configuration;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How long a request may hold an Idempotency-Key while it is being processed. After the lease, a retry may take
 * the key over (for example when the instance that held it crashed). Must be longer than a full Pricing call.
 */
@Validated
@ConfigurationProperties("delivery.idempotency")
public record IdempotencyProperties(@NotNull Duration lease) {
}
