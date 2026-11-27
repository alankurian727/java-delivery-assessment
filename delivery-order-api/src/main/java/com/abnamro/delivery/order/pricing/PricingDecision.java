package com.abnamro.delivery.order.pricing;

import com.abnamro.delivery.order.domain.Money;
import java.util.UUID;

/** A validated answer from Pricing: either an eligible price or a rejection code. */
public record PricingDecision(UUID pricingRequestId, Money price, String rejectionCode) {

    public static PricingDecision eligible(UUID pricingRequestId, Money price) {
        return new PricingDecision(pricingRequestId, price, null);
    }

    public static PricingDecision rejected(UUID pricingRequestId, String rejectionCode) {
        return new PricingDecision(pricingRequestId, null, rejectionCode);
    }

    public boolean isEligible() {
        return rejectionCode == null;
    }
}
