package com.abnamro.delivery.pricing.domain;

import java.util.Locale;
import java.util.regex.Pattern;

public record PostalCode(String value) {

    private static final Pattern FORMAT = Pattern.compile("^[1-9][0-9]{3}[A-Z]{2}$");

    public PostalCode {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid Dutch postal code");
        }
    }

    /** Normalises "1012 ab" to "1012AB". */
    public static PostalCode of(String raw) {
        return new PostalCode(raw.replace(" ", "").toUpperCase(Locale.ROOT));
    }
}
