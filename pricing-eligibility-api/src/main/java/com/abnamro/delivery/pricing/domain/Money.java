package com.abnamro.delivery.pricing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** An amount in euros, always with two decimals. */
public record Money(BigDecimal amount) {

    public static final Money ZERO = new Money(BigDecimal.ZERO);

    public Money {
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("Negative amount");
        }
        amount = amount.setScale(2, RoundingMode.HALF_UP);
    }

    public Money plus(Money other) {
        return new Money(amount.add(other.amount));
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }
}
