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

/** Read model of an idempotency record. It is written by the bind insert and the outcome update in the repository. */
@Entity
@Table(name = "idempotency_record")
public class IdempotencyRecordEntity {

    @Id
    private String idempotencyKey;

    @Column(nullable = false)
    private String requestFingerprint;

    @Enumerated(EnumType.STRING)
    private IdempotencyOutcome outcome;    // null until the request is decided

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
