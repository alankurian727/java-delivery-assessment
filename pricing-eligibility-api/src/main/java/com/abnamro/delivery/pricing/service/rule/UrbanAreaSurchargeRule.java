package com.abnamro.delivery.pricing.service.rule;

import com.abnamro.delivery.pricing.configuration.TariffProperties;
import com.abnamro.delivery.pricing.domain.Money;
import com.abnamro.delivery.pricing.domain.PostalCode;
import com.abnamro.delivery.pricing.domain.PriceComponent;
import com.abnamro.delivery.pricing.domain.PriceComponentType;
import com.abnamro.delivery.pricing.domain.Shipment;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Charged once when the origin or the destination is in the urban postal-code range. */
@Component
@Order(3)
public class UrbanAreaSurchargeRule implements PriceComponentRule {

    private final PostalCode from;
    private final PostalCode to;
    private final Money surcharge;

    public UrbanAreaSurchargeRule(TariffProperties tariff) {
        this.from = PostalCode.of(tariff.urbanArea().from());
        this.to = PostalCode.of(tariff.urbanArea().to());
        this.surcharge = new Money(tariff.urbanArea().surcharge());
    }

    @Override
    public Optional<PriceComponent> apply(Shipment shipment) {
        if (shipment.origin().isWithin(from, to) || shipment.destination().isWithin(from, to)) {
            return Optional.of(new PriceComponent(PriceComponentType.URBAN_AREA_SURCHARGE, surcharge));
        }
        return Optional.empty();
    }
}
