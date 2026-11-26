package com.abnamro.delivery.pricing.service.rule;

import static com.abnamro.delivery.pricing.fixture.TestData.shipment;
import static org.assertj.core.api.Assertions.assertThat;

import com.abnamro.delivery.pricing.domain.DeliveryType;
import com.abnamro.delivery.pricing.domain.RejectionCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SameDayRouteRuleTest {

    private final SameDayRouteRule rule = new SameDayRouteRule();

    @Test
    void allowsSameDayWithinTheSameFourDigitArea() {
        assertThat(rule.check(shipment("5611EM", "5611AB", 100, DeliveryType.SAME_DAY))).isEmpty();
    }

    @Test
    void rejectsSameDayBetweenDifferentAreas() {
        assertThat(rule.check(shipment("5611EM", "5612EM", 100, DeliveryType.SAME_DAY)))
                .hasValueSatisfying(rejection ->
                        assertThat(rejection.code()).isEqualTo(RejectionCode.SAME_DAY_NOT_SUPPORTED));
    }

    @ParameterizedTest
    @EnumSource(value = DeliveryType.class, names = {"STANDARD", "NEXT_DAY"})
    void ignoresOtherDeliveryTypes(DeliveryType deliveryType) {
        assertThat(rule.check(shipment("5611EM", "1012AB", 100, deliveryType))).isEmpty();
    }
}
