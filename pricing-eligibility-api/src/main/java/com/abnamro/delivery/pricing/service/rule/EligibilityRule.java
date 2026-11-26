package com.abnamro.delivery.pricing.service.rule;

import com.abnamro.delivery.pricing.domain.Rejection;
import com.abnamro.delivery.pricing.domain.Shipment;
import java.util.Optional;

/** Decides whether a shipment can be delivered. Implementations run in {@code @Order} order. */
public interface EligibilityRule {

    Optional<Rejection> check(Shipment shipment);
}
