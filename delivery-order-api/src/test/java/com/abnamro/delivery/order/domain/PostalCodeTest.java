package com.abnamro.delivery.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PostalCodeTest {

    @ParameterizedTest
    @ValueSource(strings = {"1012AB", "1012 AB", "1012ab", "1012 ab"})
    void normalisesCommonDutchFormats(String raw) {
        assertThat(PostalCode.of(raw).value()).isEqualTo("1012AB");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0123AB", "1012A", "10123AB", "ABCD12"})
    void rejectsInvalidPostalCodes(String raw) {
        assertThatThrownBy(() -> PostalCode.of(raw)).isInstanceOf(IllegalArgumentException.class);
    }
}
