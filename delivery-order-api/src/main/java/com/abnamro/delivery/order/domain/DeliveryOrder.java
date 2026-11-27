package com.abnamro.delivery.order.domain;

import java.time.Instant;
import java.util.UUID;

/** A confirmed delivery order with the price that was confirmed when it was created. */
public record DeliveryOrder(UUID id, String customerReference, Money price, Instant createdAt) {
}
