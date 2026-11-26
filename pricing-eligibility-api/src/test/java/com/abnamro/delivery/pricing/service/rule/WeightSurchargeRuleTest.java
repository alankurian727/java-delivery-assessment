package com.abnamro.delivery.pricing.service.rule;

import static com.abnamro.delivery.pricing.fixture.TestData.shipment;
import static com.abnamro.delivery.pricing.fixture.TestData.tariff;
import static org.assertj.core.api.Assertions.assertThat;

import com.abnamro.delivery.pricing.domain.DeliveryType;
import com.abnamro.delivery.pricing.domain.Money;
import com.abnamro.delivery.pricing.domain.PriceComponent;
import com.abnamro.delivery.pricing.domain.PriceComponentType;
import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class WeightSurchargeRuleTest {

    private final WeightSurchargeRule rule = new WeightSurchargeRule(tariff());

    @ParameterizedTest
    @ValueSource(longs = {1, 2000})
    void addsNoComponentUpTo2000Grams(long weight) {
        assertThat(rule.apply(shipment("5611EM", "3011AB", weight, DeliveryType.STANDARD))).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"2001,1.00", "5000,1.00", "5001,2.50", "10000,2.50", "10001,5.00", "20000,5.00"})
    void appliesSurchargeForWeightBand(long weight, String expected) {
        assertThat(rule.apply(shipment("5611EM", "3011AB", weight, DeliveryType.STANDARD)))
                .contains(new PriceComponent(PriceComponentType.WEIGHT_SURCHARGE, new Money(new BigDecimal(expected))));
    }
}
