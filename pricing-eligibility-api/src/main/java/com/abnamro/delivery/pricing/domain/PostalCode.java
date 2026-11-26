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

    /** The four-digit area, for example "1012" for "1012AB". */
    public String digits() {
        return value.substring(0, 4);
    }

    /** Inclusive range check. String order works because values are normalised and fixed-length. */
    public boolean isWithin(PostalCode from, PostalCode to) {
        return value.compareTo(from.value) >= 0 && value.compareTo(to.value) <= 0;
    }
}
