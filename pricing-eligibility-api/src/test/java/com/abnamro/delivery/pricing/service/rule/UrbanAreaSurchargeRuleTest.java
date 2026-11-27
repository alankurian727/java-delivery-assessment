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

class UrbanAreaSurchargeRuleTest {

    private static final PriceComponent URBAN_SURCHARGE =
            new PriceComponent(PriceComponentType.URBAN_AREA_SURCHARGE, new Money(new BigDecimal("1.00")));

    private final UrbanAreaSurchargeRule rule = new UrbanAreaSurchargeRule(tariff());

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "1012AB,5611EM",   // origin urban
            "5611EM,1012AB",   // destination urban
            "1012AB,1099ZZ",   // both urban: charged once
            "1000AA,5611EM",   // lower edge
            "1099ZZ,5611EM"})  // upper edge
    void chargesOnceWhenEitherEndIsUrban(String origin, String destination) {
        assertThat(rule.calculate(shipment(origin, destination, 100, DeliveryType.STANDARD))).contains(URBAN_SURCHARGE);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"5611EM,3011AB", "1100AA,5611EM"})
    void addsNoComponentOutsideTheUrbanArea(String origin, String destination) {
        assertThat(rule.calculate(shipment(origin, destination, 100, DeliveryType.STANDARD))).isEmpty();
    }
}
