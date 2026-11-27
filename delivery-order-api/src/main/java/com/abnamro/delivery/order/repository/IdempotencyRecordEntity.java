package com.abnamro.delivery.order.repository;

import com.abnamro.delivery.order.domain.IdempotencyOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Read model of an idempotency record. It is written once, by the insert in the repository. */
@Entity
@Table(name = "idempotency_record")
public class IdempotencyRecordEntity {

    @Id
    private String idempotencyKey;

    @Column(nullable = false)
    private String requestFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IdempotencyOutcome outcome;

    private String rejectionCode;

    private UUID orderId;

    @Column(nullable = false)
    private Instant createdAt;

    protected IdempotencyRecordEntity() {
        // for JPA
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public IdempotencyOutcome getOutcome() {
        return outcome;
    }

    public String getRejectionCode() {
        return rejectionCode;
    }

    public UUID getOrderId() {
        return orderId;
    }
}
