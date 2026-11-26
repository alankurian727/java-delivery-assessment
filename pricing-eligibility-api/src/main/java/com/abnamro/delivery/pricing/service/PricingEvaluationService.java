package com.abnamro.delivery.pricing.service;

import com.abnamro.delivery.pricing.domain.EvaluationResult;
import com.abnamro.delivery.pricing.domain.Shipment;
import org.springframework.stereotype.Service;

@Service
public class PricingEvaluationService {

    public EvaluationResult evaluate(Shipment shipment) {
        throw new UnsupportedOperationException("Pricing rules are implemented in a later step");
    }
}
