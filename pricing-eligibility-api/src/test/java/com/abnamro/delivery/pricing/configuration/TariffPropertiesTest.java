package com.abnamro.delivery.pricing.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.abnamro.delivery.pricing.configuration.TariffProperties.UrbanArea;
import com.abnamro.delivery.pricing.configuration.TariffProperties.WeightBand;
import com.abnamro.delivery.pricing.domain.DeliveryType;
import com.abnamro.delivery.pricing.fixture.TestData;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class TariffPropertiesTest {

    private static final BigDecimal ONE = new BigDecimal("1.00");

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsTheAssignmentTariff() {
        assertThat(validator.validate(TestData.tariff())).isEmpty();
    }

    @Test
    void rejectsMissingBasePriceForADeliveryType() {
        TariffProperties tariff = withBasePrices(Map.of(DeliveryType.STANDARD, ONE, DeliveryType.NEXT_DAY, ONE));

        assertThat(violations(tariff)).contains("basePriceForEveryDeliveryType");
    }

    @Test
    void rejectsDescendingWeightBands() {
        TariffProperties tariff = withWeightBands(List.of(band(5000), band(2000), band(20000)));

        assertThat(violations(tariff)).contains("weightBandsStrictlyAscending");
    }

    @Test
    void rejectsDuplicateWeightThresholds() {
        TariffProperties tariff = withWeightBands(List.of(band(2000), band(2000), band(20000)));

        assertThat(violations(tariff)).contains("weightBandsStrictlyAscending");
    }

    @Test
    void rejectsWeightBandsThatDoNotCoverTheMaximumWeight() {
        TariffProperties tariff = withWeightBands(List.of(band(2000), band(10000)));

        assertThat(violations(tariff)).contains("maxWeightCoveredByWeightBands");
    }

    @Test
    void rejectsInvalidUrbanPostalCodes() {
        assertThat(violations(withUrbanArea("ABCDEF", "1099ZZ"))).contains("urbanArea.validRange");
    }

    @Test
    void rejectsUrbanRangeWithStartAfterEnd() {
        assertThat(violations(withUrbanArea("1099ZZ", "1000AA"))).contains("urbanArea.validRange");
    }

    @Test
    void invalidTariffConfigurationStopsTheApplicationFromStarting() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
                .withUserConfiguration(TariffConfiguration.class)
                .withPropertyValues(
                        "pricing.tariff.base-prices.STANDARD=4.95",
                        "pricing.tariff.base-prices.NEXT_DAY=6.95",
                        "pricing.tariff.base-prices.SAME_DAY=11.95",
                        "pricing.tariff.max-weight-in-grams=20000",
                        "pricing.tariff.weight-bands[0].up-to-grams=5000",
                        "pricing.tariff.weight-bands[0].surcharge=1.00",
                        "pricing.tariff.weight-bands[1].up-to-grams=2000",
                        "pricing.tariff.weight-bands[1].surcharge=0.00",
                        "pricing.tariff.urban-area.from=1000AA",
                        "pricing.tariff.urban-area.to=1099ZZ",
                        "pricing.tariff.urban-area.surcharge=1.00")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("weightBandsStrictlyAscending"));
    }

    @Configuration
    @EnableConfigurationProperties(TariffProperties.class)
    static class TariffConfiguration {
    }

    private Set<String> violations(TariffProperties tariff) {
        return validator.validate(tariff).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static WeightBand band(long upToGrams) {
        return new WeightBand(upToGrams, ONE);
    }

    private static TariffProperties withBasePrices(Map<DeliveryType, BigDecimal> basePrices) {
        TariffProperties t = TestData.tariff();
        return new TariffProperties(basePrices, t.maxWeightInGrams(), t.weightBands(), t.urbanArea());
    }

    private static TariffProperties withWeightBands(List<WeightBand> bands) {
        TariffProperties t = TestData.tariff();
        return new TariffProperties(t.basePrices(), t.maxWeightInGrams(), bands, t.urbanArea());
    }

    private static TariffProperties withUrbanArea(String from, String to) {
        TariffProperties t = TestData.tariff();
        return new TariffProperties(t.basePrices(), t.maxWeightInGrams(), t.weightBands(), new UrbanArea(from, to, ONE));
    }
}
