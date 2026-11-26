package com.abnamro.delivery.pricing.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Outcome of a pricing evaluation. An eligible result has a total and components and no rejection;
 * a rejected result has a rejection and no total. Use the factory methods to create one.
 */
public record EvaluationResult(UUID requestId, Rejection rejection, Money total,
                               List<PriceComponent> components, Instant evaluatedAt) {

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
