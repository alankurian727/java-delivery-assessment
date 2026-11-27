package com.abnamro.delivery.order.repository;

import com.abnamro.delivery.order.domain.DeliveryOrder;
import com.abnamro.delivery.order.domain.DeliveryType;
import com.abnamro.delivery.order.domain.Money;
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

    public DeliveryOrderEntity(UUID id, String customerReference, String senderName, String senderPostalCode,
                               String senderCountryCode, String recipientName, String recipientPostalCode,
                               String recipientCountryCode, long weightInGrams, DeliveryType deliveryType,
                               BigDecimal priceAmount, String priceCurrency, UUID pricingRequestId, Instant createdAt) {
        this.id = id;
        this.customerReference = customerReference;
        this.senderName = senderName;
        this.senderPostalCode = senderPostalCode;
        this.senderCountryCode = senderCountryCode;
        this.recipientName = recipientName;
        this.recipientPostalCode = recipientPostalCode;
        this.recipientCountryCode = recipientCountryCode;
        this.weightInGrams = weightInGrams;
        this.deliveryType = deliveryType;
        this.status = "CONFIRMED";
        this.priceAmount = priceAmount;
        this.priceCurrency = priceCurrency;
        this.pricingRequestId = pricingRequestId;
        this.createdAt = createdAt;
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
