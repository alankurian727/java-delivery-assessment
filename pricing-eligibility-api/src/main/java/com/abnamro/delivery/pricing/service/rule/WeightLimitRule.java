package com.abnamro.delivery.pricing.service.rule;

import com.abnamro.delivery.pricing.configuration.TariffProperties;
import com.abnamro.delivery.pricing.domain.Rejection;
import com.abnamro.delivery.pricing.domain.RejectionCode;
import com.abnamro.delivery.pricing.domain.Shipment;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(1)
public class WeightLimitRule implements EligibilityRule {

    private final long maxWeightInGrams;

    public WeightLimitRule(TariffProperties tariff) {
        this.maxWeightInGrams = tariff.maxWeightInGrams();
    }

    @Override
    public Optional<Rejection> check(Shipment shipment) {
        if (shipment.weightInGrams() <= maxWeightInGrams) {
            return Optional.empty();
        }
        return Optional.of(new Rejection(RejectionCode.WEIGHT_LIMIT_EXCEEDED,
                "The maximum supported parcel weight is " + maxWeightInGrams + " grams."));
    }
}
