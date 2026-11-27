package com.abnamro.delivery.order.repository;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecordEntity, String> {

    /**
     * Records the outcome of a key. Returns 0 when the key already has one: the primary key decides, also between
     * concurrent requests on different instances (PostgreSQL waits for the other insert to commit or roll back).
     */
    @Modifying
    @Query(value = """
            INSERT INTO idempotency_record
                (idempotency_key, request_fingerprint, outcome, rejection_code, order_id, created_at)
            VALUES (:key, :fingerprint, :outcome, :rejectionCode, :orderId, :now)
            ON CONFLICT (idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("key") String key, @Param("fingerprint") String fingerprint,
                       @Param("outcome") String outcome, @Param("rejectionCode") String rejectionCode,
                       @Param("orderId") UUID orderId, @Param("now") Instant now);
}
