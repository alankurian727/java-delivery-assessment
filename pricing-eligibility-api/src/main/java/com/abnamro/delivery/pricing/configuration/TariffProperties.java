package com.abnamro.delivery.pricing.configuration;

import com.abnamro.delivery.pricing.domain.DeliveryType;
import com.abnamro.delivery.pricing.domain.PostalCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Tariff values from application.yml (pricing.tariff). Rule logic stays in code; the values can
 * change without touching it. Invalid values, including inconsistent combinations, stop the
 * application at start-up instead of producing wrong prices at runtime.
 */
@Validated
@ConfigurationProperties("pricing.tariff")
public record TariffProperties(
        @NotEmpty Map<DeliveryType, @NotNull @PositiveOrZero BigDecimal> basePrices,
        @Positive long maxWeightInGrams,
        @NotEmpty List<@Valid @NotNull WeightBand> weightBands,
        @Valid @NotNull UrbanArea urbanArea) {

    /** Surcharge for weights up to and including upToGrams. */
    public record WeightBand(@Positive long upToGrams, @NotNull @PositiveOrZero BigDecimal surcharge) {
    }

    /** Postal-code range (inclusive) that receives the urban-area surcharge. */
    public record UrbanArea(@NotBlank String from, @NotBlank String to, @NotNull @PositiveOrZero BigDecimal surcharge) {

        @AssertTrue(message = "must be valid Dutch postal codes with from not after to")
        public boolean isValidRange() {
            if (from == null || to == null) {
                return true; // reported by @NotBlank
            }
            try {
                return PostalCode.of(from).value().compareTo(PostalCode.of(to).value()) <= 0;
            } catch (RuntimeException invalidPostalCode) {
                return false;
            }
        }
    }

    @AssertTrue(message = "must contain a base price for every delivery type")
    public boolean isBasePriceForEveryDeliveryType() {
        return basePrices == null || basePrices.keySet().containsAll(EnumSet.allOf(DeliveryType.class));
    }

    @AssertTrue(message = "must be listed with strictly ascending upToGrams, without duplicates")
    public boolean isWeightBandsStrictlyAscending() {
        if (weightBands == null || weightBands.stream().anyMatch(Objects::isNull)) {
            return true; // reported by @NotEmpty / @NotNull
        }
        for (int i = 1; i < weightBands.size(); i++) {
            if (weightBands.get(i).upToGrams() <= weightBands.get(i - 1).upToGrams()) {
                return false;
            }
        }
        return true;
    }

    @AssertTrue(message = "the last weight band must cover maxWeightInGrams")
    public boolean isMaxWeightCoveredByWeightBands() {
        if (weightBands == null || weightBands.isEmpty() || weightBands.getLast() == null) {
            return true; // reported by @NotEmpty / @NotNull
        }
        return weightBands.getLast().upToGrams() >= maxWeightInGrams;
    }
}
