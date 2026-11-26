package com.abnamro.delivery.pricing.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Outcome of a pricing evaluation: either eligible (a total and its components, no rejection) or
 * rejected (a rejection, no total and no components). The constructor rejects any other combination.
 */
public record EvaluationResult(UUID requestId, Rejection rejection, Money total,
                               List<PriceComponent> components, Instant evaluatedAt) {

    public EvaluationResult {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        components = components == null ? List.of() : List.copyOf(components);   // immutable, no null items
        if (rejection == null) {
            if (total == null || components.isEmpty()) {
                throw new IllegalArgumentException("An eligible result needs a total and at least one price component");
            }
            Money sum = components.stream().map(PriceComponent::amount).reduce(Money.ZERO, Money::plus);
            if (!sum.equals(total)) {
                throw new IllegalArgumentException("The total must equal the sum of the price components");
            }
        } else if (total != null || !components.isEmpty()) {
            throw new IllegalArgumentException("A rejected result cannot have a price");
        }
    }

    public static EvaluationResult eligible(UUID requestId, Money total, List<PriceComponent> components,
                                            Instant evaluatedAt) {
        return new EvaluationResult(requestId, null, total, components, evaluatedAt);
    }

    public static EvaluationResult rejected(UUID requestId, Rejection rejection, Instant evaluatedAt) {
        return new EvaluationResult(requestId, rejection, null, List.of(), evaluatedAt);
    }

    public boolean isEligible() {
        return rejection == null;
    }
}
