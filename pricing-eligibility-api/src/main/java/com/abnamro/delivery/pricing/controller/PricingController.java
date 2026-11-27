package com.abnamro.delivery.pricing.controller;

import com.abnamro.delivery.pricing.api.PricingApi;
import com.abnamro.delivery.pricing.api.model.PricingEvaluationRequest;
import com.abnamro.delivery.pricing.api.model.PricingEvaluationResponse;
import com.abnamro.delivery.pricing.domain.EvaluationResult;
import com.abnamro.delivery.pricing.domain.Shipment;
import com.abnamro.delivery.pricing.service.PricingEvaluationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PricingController implements PricingApi {

    private final PricingApiMapper mapper;
    private final PricingEvaluationService pricingEvaluationService;

    public PricingController(PricingApiMapper mapper, PricingEvaluationService pricingEvaluationService) {
        this.mapper = mapper;
        this.pricingEvaluationService = pricingEvaluationService;
    }

    @Override
    public ResponseEntity<PricingEvaluationResponse> evaluatePricing(
            PricingEvaluationRequest pricingEvaluationRequest, String xCorrelationID) {
        Shipment shipment = mapper.toShipment(pricingEvaluationRequest);
        EvaluationResult result = pricingEvaluationService.evaluateShipment(shipment);
        return ResponseEntity.ok(mapper.toResponse(result));
    }
}
