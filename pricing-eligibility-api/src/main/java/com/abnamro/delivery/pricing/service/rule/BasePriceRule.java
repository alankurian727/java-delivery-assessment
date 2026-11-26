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
    public Optional<PriceComponent> apply(Shipment shipment) {
        BigDecimal basePrice = basePrices.get(shipment.deliveryType());
        if (basePrice == null) {
            throw new IllegalStateException("No base price configured for " + shipment.deliveryType());
        }
        Money amount = new Money(basePrice);
        log.debug("event=pricing.rule rule=BasePriceRule result=applied deliveryType={} amount={}",
                shipment.deliveryType(), amount.amount());
        return Optional.of(new PriceComponent(PriceComponentType.BASE_PRICE, amount));
    }
}
