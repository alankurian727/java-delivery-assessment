package com.abnamro.delivery.pricing.configuration;

import com.abnamro.delivery.pricing.domain.DeliveryType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Tariff values from application.yml (pricing.tariff). Rule logic stays in code; the values can
 * change without touching it. Invalid values stop the application at start-up.
 */
@Validated
@ConfigurationProperties("pricing.tariff")
public record TariffProperties(
        @NotEmpty Map<DeliveryType, @NotNull @PositiveOrZero BigDecimal> basePrices,
        @Positive long maxWeightInGrams,
        @NotEmpty List<@Valid @NotNull WeightBand> weightBands,
        @Valid @NotNull UrbanArea urbanArea) {

    /** Surcharge for weights up to and including upToGrams. Bands are listed in ascending order. */
    public record WeightBand(@Positive long upToGrams, @NotNull @PositiveOrZero BigDecimal surcharge) {
    }

    /** Postal-code range (inclusive) that receives the urban-area surcharge. */
    public record UrbanArea(@NotBlank String from, @NotBlank String to, @NotNull @PositiveOrZero BigDecimal surcharge) {
    }
}
