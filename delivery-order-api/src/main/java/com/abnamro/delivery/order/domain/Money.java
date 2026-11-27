package com.abnamro.delivery.order.domain;

import java.math.BigDecimal;

public record Money(BigDecimal amount, String currency) {
}
