package com.abnamro.delivery.pricing.configuration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Logs the tariff a running instance uses, so a price change made in configuration is visible in the logs. */
@Component
public class TariffLoadedLogger {

    private static final Logger log = LoggerFactory.getLogger(TariffLoadedLogger.class);

    private final TariffProperties tariff;

    public TariffLoadedLogger(TariffProperties tariff) {
        this.tariff = tariff;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logTariff() {
        log.atInfo().setMessage("Tariff loaded")
                .addKeyValue("event", "pricing.tariff_loaded")
                .addKeyValue("basePrices", tariff.basePrices())
                .addKeyValue("maxWeightInGrams", tariff.maxWeightInGrams())
                .addKeyValue("weightBands", tariff.weightBands().stream()
                        .map(band -> band.upToGrams() + "g:" + band.surcharge()).toList())
                .addKeyValue("urbanArea", tariff.urbanArea().from() + "-" + tariff.urbanArea().to())
                .addKeyValue("urbanSurcharge", tariff.urbanArea().surcharge())
                .log();
    }
}
