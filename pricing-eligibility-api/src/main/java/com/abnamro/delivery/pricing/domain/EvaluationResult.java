package com.abnamro.delivery.pricing.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public sealed interface EvaluationResult permits EvaluationResult.Eligible, EvaluationResult.Rejected {

    UUID requestId();

    Instant evaluatedAt();

    record Eligible(UUID requestId, Money total, List<PriceComponent> components, Instant evaluatedAt)
            implements EvaluationResult {
    }

    record Rejected(UUID requestId, Rejection rejection, Instant evaluatedAt) implements EvaluationResult {
    }
}
