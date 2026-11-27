package com.abnamro.delivery.order.repository;

import com.abnamro.delivery.order.domain.IdempotencyState;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Every write is conditional, so concurrent requests and multiple instances cannot both own a key:
 * the insert relies on the primary key, the updates on the version the owner holds.
 */
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecordEntity, String> {

    /** Returns 1 when this request claimed the key, 0 when the key already exists. */
    @Modifying
    @Query(value = """
            INSERT INTO idempotency_record
                (idempotency_key, request_fingerprint, state, lease_until, version, created_at, updated_at)
            VALUES (:key, :fingerprint, 'IN_PROGRESS', :leaseUntil, 0, :now, :now)
            ON CONFLICT (idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("key") String key, @Param("fingerprint") String fingerprint,
                       @Param("leaseUntil") Instant leaseUntil, @Param("now") Instant now);

    /** Takes over a record whose owner stopped (lease expired); returns 0 when another request changed it first. */
    @Modifying
    @Query("""
            UPDATE IdempotencyRecordEntity r
               SET r.state = com.abnamro.delivery.order.domain.IdempotencyState.IN_PROGRESS,
                   r.leaseUntil = :leaseUntil, r.version = r.version + 1, r.updatedAt = :now
             WHERE r.idempotencyKey = :key AND r.version = :version
            """)
    int takeOver(@Param("key") String key, @Param("version") long version,
                 @Param("leaseUntil") Instant leaseUntil, @Param("now") Instant now);

    /** Removes the record so the key can be used again; returns 0 when this request no longer owns the key. */
    @Modifying
    @Query("""
            DELETE FROM IdempotencyRecordEntity r
             WHERE r.idempotencyKey = :key AND r.version = :version
               AND r.state = com.abnamro.delivery.order.domain.IdempotencyState.IN_PROGRESS
            """)
    int release(@Param("key") String key, @Param("version") long version);

    /** Records the final outcome; returns 0 when this request no longer owns the key. */
    @Modifying
    @Query("""
            UPDATE IdempotencyRecordEntity r
               SET r.state = :state, r.rejectionCode = :rejectionCode, r.orderId = :orderId,
                   r.version = r.version + 1, r.updatedAt = :now
             WHERE r.idempotencyKey = :key AND r.version = :version
               AND r.state = com.abnamro.delivery.order.domain.IdempotencyState.IN_PROGRESS
            """)
    int finish(@Param("key") String key, @Param("version") long version, @Param("state") IdempotencyState state,
               @Param("rejectionCode") String rejectionCode, @Param("orderId") UUID orderId,
               @Param("now") Instant now);
}
