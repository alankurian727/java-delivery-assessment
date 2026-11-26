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
        log.info("event=pricing.tariff_loaded basePrices={} maxWeightInGrams={} weightBands={} urbanArea={}-{} urbanSurcharge={}",
                tariff.basePrices(),
                tariff.maxWeightInGrams(),
                tariff.weightBands().stream().map(band -> band.upToGrams() + "g:" + band.surcharge()).toList(),
                tariff.urbanArea().from(),
                tariff.urbanArea().to(),
                tariff.urbanArea().surcharge());
    }
}
