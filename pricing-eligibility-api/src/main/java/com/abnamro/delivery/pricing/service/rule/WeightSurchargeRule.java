package com.abnamro.delivery.pricing.service.rule;

import com.abnamro.delivery.pricing.configuration.TariffProperties;
import com.abnamro.delivery.pricing.configuration.TariffProperties.WeightBand;
import com.abnamro.delivery.pricing.domain.Money;
import com.abnamro.delivery.pricing.domain.PriceComponent;
import com.abnamro.delivery.pricing.domain.PriceComponentType;
import com.abnamro.delivery.pricing.domain.Shipment;
import java.util.List;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Surcharge from the first weight band that covers the parcel. A zero surcharge is left out of the price. */
@Component
@Order(2)
public class WeightSurchargeRule implements PriceComponentRule {

    private final List<WeightBand> weightBands;

    public WeightSurchargeRule(TariffProperties tariff) {
        this.weightBands = tariff.weightBands();
    }

    @Override
    public Optional<PriceComponent> apply(Shipment shipment) {
        Money surcharge = weightBands.stream()
                .filter(band -> shipment.weightInGrams() <= band.upToGrams())
                .findFirst()
                .map(band -> new Money(band.surcharge()))
                .orElseThrow(() -> new IllegalStateException("No weight band covers " + shipment.weightInGrams() + " grams"));
        if (surcharge.isZero()) {
            return Optional.empty();
        }
        return Optional.of(new PriceComponent(PriceComponentType.WEIGHT_SURCHARGE, surcharge));
    }
}
