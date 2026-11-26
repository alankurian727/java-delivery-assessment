package com.abnamro.delivery.pricing.service.rule;

import static com.abnamro.delivery.pricing.support.TestData.shipment;
import static com.abnamro.delivery.pricing.support.TestData.tariff;
import static org.assertj.core.api.Assertions.assertThat;

import com.abnamro.delivery.pricing.domain.DeliveryType;
import com.abnamro.delivery.pricing.domain.Rejection;
import com.abnamro.delivery.pricing.domain.RejectionCode;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WeightLimitRuleTest {

    private final WeightLimitRule rule = new WeightLimitRule(tariff());

    @ParameterizedTest
    @ValueSource(longs = {1, 2000, 19999, 20000})
    void acceptsWeightsUpToTheLimit(long weight) {
        assertThat(rule.check(shipment("5611EM", "3011AB", weight, DeliveryType.STANDARD))).isEmpty();
    }

    @Test
    void rejectsWeightAboveTheLimit() {
        Optional<Rejection> rejection = rule.check(shipment("5611EM", "3011AB", 20001, DeliveryType.STANDARD));

        assertThat(rejection).contains(new Rejection(RejectionCode.WEIGHT_LIMIT_EXCEEDED,
                "The maximum supported parcel weight is 20000 grams."));
    }
}
