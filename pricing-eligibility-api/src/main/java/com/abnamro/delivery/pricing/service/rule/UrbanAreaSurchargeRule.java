package com.abnamro.delivery.pricing.service.rule;

import com.abnamro.delivery.pricing.configuration.TariffProperties;
import com.abnamro.delivery.pricing.domain.Money;
import com.abnamro.delivery.pricing.domain.PostalCode;
import com.abnamro.delivery.pricing.domain.PriceComponent;
import com.abnamro.delivery.pricing.domain.PriceComponentType;
import com.abnamro.delivery.pricing.domain.Shipment;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Charged once when the origin or the destination is in the urban postal-code range. */
@Component
@Order(3)
public class UrbanAreaSurchargeRule implements PriceComponentRule {

    private static final Logger log = LoggerFactory.getLogger(UrbanAreaSurchargeRule.class);

    private final PostalCode urbanAreaStart;
    private final PostalCode urbanAreaEnd;
    private final Money surcharge;

    public UrbanAreaSurchargeRule(TariffProperties tariff) {
        this.urbanAreaStart = PostalCode.of(tariff.urbanArea().from());
        this.urbanAreaEnd = PostalCode.of(tariff.urbanArea().to());
        this.surcharge = new Money(tariff.urbanArea().surcharge());
    }

    @Override
    public Optional<PriceComponent> calculate(Shipment shipment) {
        boolean originUrban = shipment.origin().isWithin(urbanAreaStart, urbanAreaEnd);
        boolean destinationUrban = shipment.destination().isWithin(urbanAreaStart, urbanAreaEnd);
        boolean surchargeApplies = originUrban || destinationUrban;
        log.atDebug().setMessage("Pricing rule evaluated")
                .addKeyValue("event", "pricing.rule")
                .addKeyValue("rule", "UrbanAreaSurchargeRule")
                .addKeyValue("result", surchargeApplies ? "applied" : "skipped")
                .addKeyValue("originUrban", originUrban)
                .addKeyValue("destinationUrban", destinationUrban)
                .log();
        if (surchargeApplies) {
            return Optional.of(new PriceComponent(PriceComponentType.URBAN_AREA_SURCHARGE, surcharge));
        }
        return Optional.empty();
    }
}
