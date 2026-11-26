package com.abnamro.delivery.pricing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @ParameterizedTest
    @CsvSource({"1,1.00", "6.95,6.95", "1.005,1.01", "1.004,1.00", "0,0.00"})
    void keepsTwoDecimalsRoundingHalfUp(String amount, String expected) {
        assertThat(new Money(new BigDecimal(amount)).amount()).isEqualTo(new BigDecimal(expected));
    }

    @Test
    void addsWithoutFloatingPointErrors() {
        Money total = Money.ZERO.plus(new Money(new BigDecimal("6.95")))
                .plus(new Money(new BigDecimal("1.00")))
                .plus(new Money(new BigDecimal("1.00")));

        assertThat(total.amount()).isEqualTo(new BigDecimal("8.95"));
    }

    @Test
    void rejectsNegativeAmounts() {
        assertThatThrownBy(() -> new Money(new BigDecimal("-0.01"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void detectsZero() {
        assertThat(new Money(new BigDecimal("0.00")).isZero()).isTrue();
        assertThat(new Money(new BigDecimal("0.01")).isZero()).isFalse();
    }
}
