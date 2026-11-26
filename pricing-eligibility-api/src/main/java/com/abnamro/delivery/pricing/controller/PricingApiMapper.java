package com.abnamro.delivery.pricing.controller;

import com.abnamro.delivery.pricing.api.model.EligibleEvaluation;
import com.abnamro.delivery.pricing.api.model.PricingEvaluationRequest;
import com.abnamro.delivery.pricing.api.model.PricingEvaluationResponse;
import com.abnamro.delivery.pricing.api.model.RejectedEvaluation;
import com.abnamro.delivery.pricing.domain.EvaluationResult;
import com.abnamro.delivery.pricing.domain.Money;
import com.abnamro.delivery.pricing.domain.PostalCode;
import com.abnamro.delivery.pricing.domain.Shipment;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/**
 * Maps between the generated API models and the pricing domain. MapStruct generates the
 * implementation at build time; an unmapped target field fails the build.
 * Generated types that share a name with a domain type are written with their full name.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface PricingApiMapper {

    @Mapping(target = "origin", source = "originPostalCode")
    @Mapping(target = "destination", source = "destinationPostalCode")
    Shipment toShipment(PricingEvaluationRequest request);

    default PricingEvaluationResponse toResponse(EvaluationResult result) {
        return switch (result) {
            case EvaluationResult.Eligible eligible -> toEligibleEvaluation(eligible);
            case EvaluationResult.Rejected rejected -> toRejectedEvaluation(rejected);
        };
    }

    @Mapping(target = "eligible", constant = "true")
    @Mapping(target = "price", source = "total")
    @Mapping(target = "priceComponents", source = "components")
    EligibleEvaluation toEligibleEvaluation(EvaluationResult.Eligible eligible);

    @Mapping(target = "eligible", constant = "false")
    @Mapping(target = "rejectionCode", source = "rejection.code")
    @Mapping(target = "message", source = "rejection.message")
    RejectedEvaluation toRejectedEvaluation(EvaluationResult.Rejected rejected);

    @Mapping(target = "currency", constant = "EUR")
    com.abnamro.delivery.pricing.api.model.Money toApiMoney(Money money);

    default PostalCode toPostalCode(String raw) {
        return PostalCode.of(raw);
    }

    default BigDecimal toAmount(Money money) {
        return money.amount();
    }

    default OffsetDateTime toUtc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
