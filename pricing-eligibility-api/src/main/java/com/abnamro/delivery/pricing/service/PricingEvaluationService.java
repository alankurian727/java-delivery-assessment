package com.abnamro.delivery.pricing.service;

import com.abnamro.delivery.pricing.domain.EvaluationResult;
import com.abnamro.delivery.pricing.domain.Money;
import com.abnamro.delivery.pricing.domain.PriceComponent;
import com.abnamro.delivery.pricing.domain.Rejection;
import com.abnamro.delivery.pricing.domain.Shipment;
import com.abnamro.delivery.pricing.service.rule.EligibilityRule;
import com.abnamro.delivery.pricing.service.rule.PriceComponentRule;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs the eligibility rules in order and returns the first rejection. For an eligible shipment it
 * runs the price rules and sums their components. New rules are added as beans; this class does not change.
 */
@Service
public class PricingEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(PricingEvaluationService.class);

    private final List<EligibilityRule> eligibilityRules;
    private final List<PriceComponentRule> priceComponentRules;
    private final Clock clock;

    public PricingEvaluationService(List<EligibilityRule> eligibilityRules,
                                    List<PriceComponentRule> priceComponentRules,
                                    Clock clock) {
        this.eligibilityRules = eligibilityRules;
        this.priceComponentRules = priceComponentRules;
        this.clock = clock;
    }

    public EvaluationResult evaluate(Shipment shipment) {
        Instant evaluatedAt = clock.instant();

        for (EligibilityRule rule : eligibilityRules) {
            Optional<Rejection> rejection = rule.check(shipment);
            if (rejection.isPresent()) {
                log.atInfo().setMessage("Pricing evaluated")
                        .addKeyValue("event", "pricing.evaluated")
                        .addKeyValue("pricingRequestId", shipment.requestId())
                        .addKeyValue("outcome", "REJECTED")
                        .addKeyValue("rejectionCode", rejection.get().code())
                        .log();
                return EvaluationResult.rejected(shipment.requestId(), rejection.get(), evaluatedAt);
            }
        }

        List<PriceComponent> components = priceComponentRules.stream()
                .map(rule -> rule.apply(shipment))
                .flatMap(Optional::stream)
                .toList();
        Money total = components.stream()
                .map(PriceComponent::amount)
                .reduce(Money.ZERO, Money::plus);
        log.atInfo().setMessage("Pricing evaluated")
                .addKeyValue("event", "pricing.evaluated")
                .addKeyValue("pricingRequestId", shipment.requestId())
                .addKeyValue("outcome", "ELIGIBLE")
                .addKeyValue("deliveryType", shipment.deliveryType())
                .addKeyValue("total", total.amount())
                .log();
        return EvaluationResult.eligible(shipment.requestId(), total, components, evaluatedAt);
    }
}
