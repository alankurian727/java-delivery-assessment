package com.abnamro.delivery.order.repository;

import com.abnamro.delivery.order.domain.DeliveryOrder;
import com.abnamro.delivery.order.domain.DeliveryType;
import com.abnamro.delivery.order.domain.Money;
import com.abnamro.delivery.order.domain.OrderRequest;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "delivery_order")
public class DeliveryOrderEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String customerReference;

    @Column(nullable = false)
    private String senderName;

    @Column(nullable = false)
    private String senderPostalCode;

    @Column(nullable = false)
    private String senderCountryCode;

    @Column(nullable = false)
    private String recipientName;

    @Column(nullable = false)
    private String recipientPostalCode;

    @Column(nullable = false)
    private String recipientCountryCode;

    @Column(nullable = false)
    private long weightInGrams;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeliveryType deliveryType;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private BigDecimal priceAmount;

    @Column(nullable = false)
    private String priceCurrency;

    @Column(nullable = false)
    private UUID pricingRequestId;

    @Column(nullable = false)
    private Instant createdAt;

    protected DeliveryOrderEntity() {
        // for JPA
    }

    /** A confirmed order: the normalised request and the price Pricing confirmed for it. */
    public static DeliveryOrderEntity confirmed(UUID id, OrderRequest request, Money price, UUID pricingRequestId,
                                                Instant createdAt) {
        DeliveryOrderEntity order = new DeliveryOrderEntity();
        order.id = id;
        order.customerReference = request.customerReference();
        order.senderName = request.sender().name();
        order.senderPostalCode = request.sender().postalCode().value();
        order.senderCountryCode = request.sender().countryCode();
        order.recipientName = request.recipient().name();
        order.recipientPostalCode = request.recipient().postalCode().value();
        order.recipientCountryCode = request.recipient().countryCode();
        order.weightInGrams = request.weightInGrams();
        order.deliveryType = request.deliveryType();
        order.status = "CONFIRMED";
        order.priceAmount = price.amount();
        order.priceCurrency = price.currency();
        order.pricingRequestId = pricingRequestId;
        order.createdAt = createdAt;
        return order;
    }

    // Mapped here because it is a few fields; the entity may depend on the domain, never the reverse.
    // If entity mapping grows, move it to a dedicated mapper in this package.
    public DeliveryOrder toDomain() {
        return new DeliveryOrder(id, customerReference, new Money(priceAmount, priceCurrency), createdAt);
    }

    public UUID getId() {
        return id;
    }
}
