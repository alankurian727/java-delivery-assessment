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

class BasePriceRuleTest {

    private final BasePriceRule rule = new BasePriceRule(tariff());

    @ParameterizedTest
    @CsvSource({"STANDARD,4.95", "NEXT_DAY,6.95", "SAME_DAY,11.95"})
    void appliesBasePricePerDeliveryType(DeliveryType deliveryType, String expected) {
        assertThat(rule.calculate(shipment("5611EM", "5611AB", 100, deliveryType)))
                .contains(new PriceComponent(PriceComponentType.BASE_PRICE, new Money(new BigDecimal(expected))));
    }
}
