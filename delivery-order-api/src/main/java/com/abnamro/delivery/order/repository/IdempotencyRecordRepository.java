package com.abnamro.delivery.order.repository;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecordEntity, String> {

    /**
     * Binds the key to the request's fingerprint, without an outcome. Returns 0 when the key is already bound: the
     * primary key decides, also between concurrent requests on different instances.
     */
    @Modifying
    @Query(value = """
            INSERT INTO idempotency_record (idempotency_key, request_fingerprint, created_at)
            VALUES (:key, :fingerprint, :now)
            ON CONFLICT (idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int bindIfAbsent(@Param("key") String key, @Param("fingerprint") String fingerprint, @Param("now") Instant now);

    /**
     * Records the outcome of a bound key. Returns 0 when the key already has one. A concurrent update of the same
     * key waits for the first to commit; PostgreSQL then re-checks {@code outcome IS NULL}, so only one request decides.
     */
    @Modifying
    @Query(value = """
            UPDATE idempotency_record
               SET outcome = :outcome, rejection_code = :rejectionCode, order_id = :orderId
             WHERE idempotency_key = :key AND outcome IS NULL
            """, nativeQuery = true)
    int recordOutcome(@Param("key") String key, @Param("outcome") String outcome,
                      @Param("rejectionCode") String rejectionCode, @Param("orderId") UUID orderId);
}
