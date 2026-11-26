package com.abnamro.delivery.pricing.controller;

import com.abnamro.delivery.pricing.api.model.EligibleEvaluation;
import com.abnamro.delivery.pricing.api.model.PricingEvaluationRequest;
import com.abnamro.delivery.pricing.api.model.PricingEvaluationResponse;
import com.abnamro.delivery.pricing.api.model.RejectedEvaluation;
import com.abnamro.delivery.pricing.domain.DeliveryType;
import com.abnamro.delivery.pricing.domain.EvaluationResult;
import com.abnamro.delivery.pricing.domain.Money;
import com.abnamro.delivery.pricing.domain.PostalCode;
import com.abnamro.delivery.pricing.domain.PriceComponent;
import com.abnamro.delivery.pricing.domain.Shipment;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;

/**
 * Maps between the generated API models and the pricing domain.
 * Generated types that share a name with a domain type are written with their full name.
 */
@Component
public class PricingApiMapper {

    public Shipment toShipment(PricingEvaluationRequest request) {
        return new Shipment(
                request.getRequestId(),
                PostalCode.of(request.getOriginPostalCode()),
                PostalCode.of(request.getDestinationPostalCode()),
                request.getWeightInGrams(),
                DeliveryType.valueOf(request.getDeliveryType().name()));
    }

    public PricingEvaluationResponse toResponse(EvaluationResult result) {
        return switch (result) {
            case EvaluationResult.Eligible eligible -> new EligibleEvaluation(
                    eligible.requestId(),
                    true,
                    toApiMoney(eligible.total()),
                    eligible.components().stream().map(this::toApiPriceComponent).toList(),
                    toUtc(eligible.evaluatedAt()));
            case EvaluationResult.Rejected rejected -> new RejectedEvaluation(
                    rejected.requestId(),
                    false,
                    rejected.rejection().code().name(),
                    rejected.rejection().message(),
                    toUtc(rejected.evaluatedAt()));
        };
    }

    private com.abnamro.delivery.pricing.api.model.Money toApiMoney(Money money) {
        return new com.abnamro.delivery.pricing.api.model.Money(
                money.amount(), com.abnamro.delivery.pricing.api.model.Money.CurrencyEnum.EUR);
    }

    private com.abnamro.delivery.pricing.api.model.PriceComponent toApiPriceComponent(PriceComponent component) {
        return new com.abnamro.delivery.pricing.api.model.PriceComponent(
                component.type().name(), component.amount().amount());
    }

    private static OffsetDateTime toUtc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
