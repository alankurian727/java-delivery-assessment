package com.abnamro.delivery.pricing.service.rule;

import com.abnamro.delivery.pricing.configuration.TariffProperties;
import com.abnamro.delivery.pricing.domain.DeliveryType;
import com.abnamro.delivery.pricing.domain.Money;
import com.abnamro.delivery.pricing.domain.PriceComponent;
import com.abnamro.delivery.pricing.domain.PriceComponentType;
import com.abnamro.delivery.pricing.domain.Shipment;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(1)
public class BasePriceRule implements PriceComponentRule {

    private static final Logger log = LoggerFactory.getLogger(BasePriceRule.class);

    private final Map<DeliveryType, BigDecimal> basePrices;

    public BasePriceRule(TariffProperties tariff) {
        this.basePrices = tariff.basePrices();
    }

    @Override
    public Optional<PriceComponent> calculate(Shipment shipment) {
        BigDecimal configuredPrice = basePrices.get(shipment.deliveryType());
        if (configuredPrice == null) {
            throw new IllegalStateException("No base price configured for " + shipment.deliveryType());
        }
        Money basePrice = new Money(configuredPrice);
        log.atDebug().setMessage("Pricing rule evaluated")
                .addKeyValue("event", "pricing.rule")
                .addKeyValue("rule", "BasePriceRule")
                .addKeyValue("result", "applied")
                .addKeyValue("deliveryType", shipment.deliveryType())
                .addKeyValue("amount", basePrice.amount())
                .log();
        return Optional.of(new PriceComponent(PriceComponentType.BASE_PRICE, basePrice));
    }
}
