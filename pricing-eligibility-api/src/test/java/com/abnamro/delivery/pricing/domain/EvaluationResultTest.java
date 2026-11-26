package com.abnamro.delivery.pricing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EvaluationResultTest {

    private static final UUID REQUEST_ID = UUID.fromString("7cb82db3-fe9a-49e1-a0f9-fdeae2455869");
    private static final Instant NOW = Instant.parse("2026-11-25T08:29:59Z");
    private static final PriceComponent BASE = component(PriceComponentType.BASE_PRICE, "6.95");
    private static final PriceComponent WEIGHT = component(PriceComponentType.WEIGHT_SURCHARGE, "1.00");
    private static final Rejection REJECTION = new Rejection(RejectionCode.WEIGHT_LIMIT_EXCEEDED, "Too heavy.");

    @Test
    void createsEligibleResult() {
        EvaluationResult result = EvaluationResult.eligible(REQUEST_ID, money("7.95"), List.of(BASE, WEIGHT), NOW);

        assertThat(result.isEligible()).isTrue();
        assertThat(result.components()).containsExactly(BASE, WEIGHT);
    }

    @Test
    void createsRejectedResult() {
        EvaluationResult result = EvaluationResult.rejected(REQUEST_ID, REJECTION, NOW);

        assertThat(result.isEligible()).isFalse();
        assertThat(result.total()).isNull();
        assertThat(result.components()).isEmpty();
    }

    @Test
    void requiresRequestIdAndEvaluationTime() {
        assertThatThrownBy(() -> EvaluationResult.rejected(null, REJECTION, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> EvaluationResult.rejected(REQUEST_ID, REJECTION, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsEligibleResultWithoutTotalOrComponents() {
        assertThatThrownBy(() -> EvaluationResult.eligible(REQUEST_ID, null, List.of(BASE), NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EvaluationResult.eligible(REQUEST_ID, money("0.00"), List.of(), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEligibleResultWhoseTotalIsNotTheSumOfItsComponents() {
        assertThatThrownBy(() -> EvaluationResult.eligible(REQUEST_ID, money("9.99"), List.of(BASE, WEIGHT), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsRejectedResultWithAPrice() {
        assertThatThrownBy(() -> new EvaluationResult(REQUEST_ID, REJECTION, money("6.95"), List.of(BASE), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keepsAnImmutableCopyOfTheComponents() {
        List<PriceComponent> components = new ArrayList<>(List.of(BASE));
        EvaluationResult result = EvaluationResult.eligible(REQUEST_ID, money("6.95"), components, NOW);

        components.add(WEIGHT);

        assertThat(result.components()).containsExactly(BASE);
        assertThatThrownBy(() -> result.components().add(WEIGHT)).isInstanceOf(UnsupportedOperationException.class);
    }

    private static Money money(String amount) {
        return new Money(new BigDecimal(amount));
    }

    private static PriceComponent component(PriceComponentType type, String amount) {
        return new PriceComponent(type, money(amount));
    }
}
