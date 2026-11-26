package com.abnamro.delivery.pricing.service;

import static com.abnamro.delivery.pricing.support.TestData.REQUEST_ID;
import static com.abnamro.delivery.pricing.support.TestData.shipment;
import static com.abnamro.delivery.pricing.support.TestData.tariff;
import static org.assertj.core.api.Assertions.assertThat;

import com.abnamro.delivery.pricing.configuration.TariffProperties;
import com.abnamro.delivery.pricing.domain.DeliveryType;
import com.abnamro.delivery.pricing.domain.EvaluationResult;
import com.abnamro.delivery.pricing.domain.PriceComponent;
import com.abnamro.delivery.pricing.domain.PriceComponentType;
import com.abnamro.delivery.pricing.domain.RejectionCode;
import com.abnamro.delivery.pricing.service.rule.BasePriceRule;
import com.abnamro.delivery.pricing.service.rule.SameDayRouteRule;
import com.abnamro.delivery.pricing.service.rule.UrbanAreaSurchargeRule;
import com.abnamro.delivery.pricing.service.rule.WeightLimitRule;
import com.abnamro.delivery.pricing.service.rule.WeightSurchargeRule;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Runs the real rules in their production order, with a fixed clock. */
class PricingEvaluationServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-25T08:29:59Z");

    private final TariffProperties tariff = tariff();
    private final PricingEvaluationService service = new PricingEvaluationService(
            List.of(new WeightLimitRule(tariff), new SameDayRouteRule()),
            List.of(new BasePriceRule(tariff), new WeightSurchargeRule(tariff), new UrbanAreaSurchargeRule(tariff)),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void pricesTheAssignmentExample() {
        EvaluationResult result = service.evaluate(shipment("1012AB", "5611EM", 2750, DeliveryType.NEXT_DAY));

        assertThat(result.isEligible()).isTrue();
        assertThat(result.requestId()).isEqualTo(REQUEST_ID);
        assertThat(result.evaluatedAt()).isEqualTo(NOW);
        assertThat(result.total().amount()).isEqualTo(new BigDecimal("8.95"));
        assertThat(result.components()).extracting(PriceComponent::type).containsExactly(
                PriceComponentType.BASE_PRICE, PriceComponentType.WEIGHT_SURCHARGE, PriceComponentType.URBAN_AREA_SURCHARGE);
    }

    @ParameterizedTest(name = "{0} {1}->{2} {3}g = {4}")
    @CsvSource({
            "STANDARD,5611EM,3011AB,2000,4.95",     // base only
            "STANDARD,5611EM,3011AB,2001,5.95",     // + weight 1.00
            "NEXT_DAY,5611EM,3011AB,5001,9.45",     // + weight 2.50
            "STANDARD,1012AB,1099ZZ,10001,10.95",   // + weight 5.00 + urban once
            "SAME_DAY,1012AB,1012XY,20000,17.95"})  // same area, heaviest eligible parcel
    void totalIsTheSumOfAllApplicableComponents(DeliveryType type, String origin, String destination,
                                                long weight, String expectedTotal) {
        EvaluationResult result = service.evaluate(shipment(origin, destination, weight, type));

        assertThat(result.isEligible()).isTrue();
        assertThat(result.total().amount()).isEqualTo(new BigDecimal(expectedTotal));
    }

    @Test
    void rejectsWithoutPricing() {
        EvaluationResult result = service.evaluate(shipment("1012AB", "5611EM", 100, DeliveryType.SAME_DAY));

        assertThat(result.isEligible()).isFalse();
        assertThat(result.rejection().code()).isEqualTo(RejectionCode.SAME_DAY_NOT_SUPPORTED);
        assertThat(result.total()).isNull();
        assertThat(result.components()).isEmpty();
        assertThat(result.evaluatedAt()).isEqualTo(NOW);
    }

    @Test
    void firstRejectionWins() {
        EvaluationResult result = service.evaluate(shipment("1012AB", "5611EM", 25000, DeliveryType.SAME_DAY));

        assertThat(result.rejection().code()).isEqualTo(RejectionCode.WEIGHT_LIMIT_EXCEEDED);
    }
}
