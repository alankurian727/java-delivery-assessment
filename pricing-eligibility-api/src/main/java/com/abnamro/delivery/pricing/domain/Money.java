package com.abnamro.delivery.pricing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** An amount in euros, always with two decimals. */
public record Money(BigDecimal amount) {

    public Money {
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("Negative amount");
        }
        amount = amount.setScale(2, RoundingMode.HALF_UP);
    }
}
