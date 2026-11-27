package com.abnamro.delivery.pricing.service.rule;

import com.abnamro.delivery.pricing.domain.DeliveryType;
import com.abnamro.delivery.pricing.domain.Rejection;
import com.abnamro.delivery.pricing.domain.RejectionCode;
import com.abnamro.delivery.pricing.domain.Shipment;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Same-day delivery is available only when origin and destination share the same four postal-code digits. */
@Component
@Order(2)
public class SameDayRouteRule implements EligibilityRule {

    private static final Logger log = LoggerFactory.getLogger(SameDayRouteRule.class);

    @Override
    public Optional<Rejection> check(Shipment shipment) {
        if (shipment.deliveryType() != DeliveryType.SAME_DAY) {
            log.atDebug().setMessage("Pricing rule evaluated")
                    .addKeyValue("event", "pricing.rule")
                    .addKeyValue("rule", "SameDayRouteRule")
                    .addKeyValue("result", "not_applicable")
                    .addKeyValue("deliveryType", shipment.deliveryType())
                    .log();
            return Optional.empty();
        }
        boolean sameArea = shipment.origin().digits().equals(shipment.destination().digits());
        log.atDebug().setMessage("Pricing rule evaluated")
                .addKeyValue("event", "pricing.rule")
                .addKeyValue("rule", "SameDayRouteRule")
                .addKeyValue("result", sameArea ? "passed" : "rejected")
                .addKeyValue("sameArea", sameArea)
                .log();
        if (sameArea) {
            return Optional.empty();
        }
        return Optional.of(new Rejection(RejectionCode.SAME_DAY_NOT_SUPPORTED,
                "Same-day delivery requires origin and destination in the same postal-code area."));
    }
}
