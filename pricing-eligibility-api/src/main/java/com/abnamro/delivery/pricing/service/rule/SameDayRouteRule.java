package com.abnamro.delivery.pricing.service.rule;

import com.abnamro.delivery.pricing.domain.DeliveryType;
import com.abnamro.delivery.pricing.domain.Rejection;
import com.abnamro.delivery.pricing.domain.RejectionCode;
import com.abnamro.delivery.pricing.domain.Shipment;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Same-day delivery is available only when origin and destination share the same four postal-code digits. */
@Component
@Order(2)
public class SameDayRouteRule implements EligibilityRule {

    @Override
    public Optional<Rejection> check(Shipment shipment) {
        if (shipment.deliveryType() != DeliveryType.SAME_DAY
                || shipment.origin().digits().equals(shipment.destination().digits())) {
            return Optional.empty();
        }
        return Optional.of(new Rejection(RejectionCode.SAME_DAY_NOT_SUPPORTED,
                "Same-day delivery requires origin and destination in the same postal-code area."));
    }
}
