package com.abnamro.delivery.pricing.domain;

import java.util.UUID;

public record Shipment(UUID requestId, PostalCode origin, PostalCode destination,
                       long weightInGrams, DeliveryType deliveryType) {
}
