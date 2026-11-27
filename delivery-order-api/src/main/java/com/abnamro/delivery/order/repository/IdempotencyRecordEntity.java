package com.abnamro.delivery.order.repository;

import com.abnamro.delivery.order.domain.IdempotencyState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Read model of an idempotency record. Writes go through the conditional queries in the repository. */
@Entity
@Table(name = "idempotency_record")
public class IdempotencyRecordEntity {

    @Id
    private String idempotencyKey;

    @Column(nullable = false)
    private String requestFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IdempotencyState state;

    private String rejectionCode;

    private UUID orderId;

    @Column(nullable = false)
    private Instant leaseUntil;

    @Column(nullable = false)
    private long version;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected IdempotencyRecordEntity() {
        // for JPA
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public IdempotencyState getState() {
        return state;
    }

    public String getRejectionCode() {
        return rejectionCode;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public Instant getLeaseUntil() {
        return leaseUntil;
    }

    public long getVersion() {
        return version;
    }
}
