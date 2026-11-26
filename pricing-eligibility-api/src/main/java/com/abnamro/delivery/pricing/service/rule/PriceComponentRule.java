package com.abnamro.delivery.pricing.service.rule;

import com.abnamro.delivery.pricing.domain.PriceComponent;
import com.abnamro.delivery.pricing.domain.Shipment;
import java.util.Optional;

/** Adds one component to the price of an eligible shipment. Implementations run in {@code @Order} order. */
public interface PriceComponentRule {

    Optional<PriceComponent> apply(Shipment shipment);
}
