package com.abnamro.delivery.pricing.support;

import com.abnamro.delivery.pricing.configuration.TariffProperties;
import com.abnamro.delivery.pricing.configuration.TariffProperties.UrbanArea;
import com.abnamro.delivery.pricing.configuration.TariffProperties.WeightBand;
import com.abnamro.delivery.pricing.domain.DeliveryType;
import com.abnamro.delivery.pricing.domain.PostalCode;
import com.abnamro.delivery.pricing.domain.Shipment;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The assignment's tariff (same values as application.yml) and a shipment builder for tests. */
public final class TestData {

    public static final UUID REQUEST_ID = UUID.fromString("7cb82db3-fe9a-49e1-a0f9-fdeae2455869");

    private TestData() {
    }

    public static TariffProperties tariff() {
        return new TariffProperties(
                Map.of(DeliveryType.STANDARD, new BigDecimal("4.95"),
                        DeliveryType.NEXT_DAY, new BigDecimal("6.95"),
                        DeliveryType.SAME_DAY, new BigDecimal("11.95")),
                20000,
                List.of(new WeightBand(2000, new BigDecimal("0.00")),
                        new WeightBand(5000, new BigDecimal("1.00")),
                        new WeightBand(10000, new BigDecimal("2.50")),
                        new WeightBand(20000, new BigDecimal("5.00"))),
                new UrbanArea("1000AA", "1099ZZ", new BigDecimal("1.00")));
    }

    public static Shipment shipment(String origin, String destination, long weightInGrams, DeliveryType deliveryType) {
        return new Shipment(REQUEST_ID, PostalCode.of(origin), PostalCode.of(destination), weightInGrams, deliveryType);
    }
}
