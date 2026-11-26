package com.abnamro.delivery.pricing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PostalCodeTest {

    @ParameterizedTest
    @ValueSource(strings = {"1012AB", "1012 AB", "1012ab", "1012 ab", "1012 Ab"})
    void normalisesCommonDutchFormats(String raw) {
        assertThat(PostalCode.of(raw).value()).isEqualTo("1012AB");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0123AB", "123AB", "12345A", "1012A", "1012ABC", "ABCDEF", ""})
    void rejectsInvalidPostalCodesWithoutEchoingThem(String raw) {
        assertThatThrownBy(() -> PostalCode.of(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid Dutch postal code");
    }

    @Test
    void returnsFourDigitArea() {
        assertThat(PostalCode.of("5611EM").digits()).isEqualTo("5611");
    }

    @ParameterizedTest
    @CsvSource({"1000AA,true", "1012AB,true", "1099ZZ,true", "1100AA,false", "5611EM,false"})
    void checksInclusiveRange(String postalCode, boolean expected) {
        assertThat(PostalCode.of(postalCode).isWithin(PostalCode.of("1000AA"), PostalCode.of("1099ZZ")))
                .isEqualTo(expected);
    }
}
