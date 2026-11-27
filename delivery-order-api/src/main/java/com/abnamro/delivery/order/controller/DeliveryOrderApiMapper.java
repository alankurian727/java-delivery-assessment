package com.abnamro.delivery.order.controller;

import com.abnamro.delivery.order.api.model.ConfirmedOrder;
import com.abnamro.delivery.order.api.model.CreateDeliveryOrderRequest;
import com.abnamro.delivery.order.domain.DeliveryOrder;
import com.abnamro.delivery.order.domain.OrderRequest;
import com.abnamro.delivery.order.domain.Party;
import com.abnamro.delivery.order.domain.PostalCode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Locale;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/** Maps between the generated API models and the domain. An unmapped target field fails the build. */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface DeliveryOrderApiMapper {

    ZoneId AMSTERDAM = ZoneId.of("Europe/Amsterdam");

    @Mapping(target = "weightInGrams", source = "parcel.weightInGrams")
    OrderRequest toOrderRequest(CreateDeliveryOrderRequest request);

    @Mapping(target = "orderId", source = "id")
    @Mapping(target = "status", constant = "CONFIRMED")
    ConfirmedOrder toConfirmedOrder(DeliveryOrder order);

    /** Normalises the postal code and country code, so equivalent requests compare equal. */
    default Party toParty(com.abnamro.delivery.order.api.model.Party party) {
        return new Party(party.getName(), PostalCode.of(party.getPostalCode()),
                party.getCountryCode().toUpperCase(Locale.ROOT));
    }

    /** Stored in UTC; shown with the Europe/Amsterdam offset. */
    default OffsetDateTime toAmsterdamTime(Instant instant) {
        return instant.atZone(AMSTERDAM).toOffsetDateTime();
    }
}
