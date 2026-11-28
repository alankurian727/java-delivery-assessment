package com.abnamro.delivery.order.domain;

import java.util.Locale;
import java.util.Objects;

/** Sender or recipient. The country code is normalised to upper case, so "nl" and "NL" are the same party. */
public record Party(String name, PostalCode postalCode, String countryCode) {

    public Party {
        countryCode = Objects.requireNonNull(countryCode, "countryCode").toUpperCase(Locale.ROOT);
    }
}
