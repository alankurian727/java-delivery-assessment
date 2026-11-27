package com.abnamro.delivery.order.service;

import com.abnamro.delivery.order.domain.IdempotencyOutcome;
import com.abnamro.delivery.order.exception.DeliveryRejectedException;
import com.abnamro.delivery.order.exception.IdempotencyConflictException;
import com.abnamro.delivery.order.exception.OutcomeAlreadyRecordedException;
import com.abnamro.delivery.order.repository.IdempotencyRecordEntity;
import com.abnamro.delivery.order.repository.IdempotencyRecordRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores the final outcome of each Idempotency-Key and returns it for repeated requests (ADR-008). Nothing is
 * written before the outcome is known, so there is no in-progress state to recover. Recording joins the caller's
 * transaction when there is one.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyRecordRepository recordRepository;
    private final Clock clock;

    public IdempotencyService(IdempotencyRecordRepository recordRepository, Clock clock) {
        this.recordRepository = recordRepository;
        this.clock = clock;
    }

    /**
     * Returns the order ID stored for this key, or empty when the key is new. A stored rejection is returned by
     * throwing it again; a different request under the same key is a conflict.
     */
    @Transactional(readOnly = true)
    public Optional<UUID> findPreviousOutcome(String key, String fingerprint) {
        Optional<IdempotencyRecordEntity> existing = recordRepository.findById(key);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        IdempotencyRecordEntity record = existing.get();
        if (!record.getRequestFingerprint().equals(fingerprint)) {
            logEvent("order.idempotency_conflict", null);
            throw new IdempotencyConflictException();
        }
        logEvent("order.previous_outcome_returned", record.getOutcome());
        if (record.getOutcome() == IdempotencyOutcome.REJECTED) {
            throw new DeliveryRejectedException(record.getRejectionCode());
        }
        return Optional.of(record.getOrderId());
    }

    @Transactional
    public void recordRejection(String key, String fingerprint, String rejectionCode) {
        recordOutcome(key, fingerprint, IdempotencyOutcome.REJECTED, rejectionCode, null);
    }

    /** Throws {@link OutcomeAlreadyRecordedException} when another request recorded this key first. */
    @Transactional
    public void recordConfirmation(String key, String fingerprint, UUID orderId) {
        recordOutcome(key, fingerprint, IdempotencyOutcome.CONFIRMED, null, orderId);
    }

    private void recordOutcome(String key, String fingerprint, IdempotencyOutcome outcome, String rejectionCode,
                               UUID orderId) {
        int inserted = recordRepository.insertIfAbsent(
                key, fingerprint, outcome.name(), rejectionCode, orderId, clock.instant());
        if (inserted == 0) {
            throw new OutcomeAlreadyRecordedException();
        }
    }

    private static void logEvent(String event, IdempotencyOutcome outcome) {
        var logEntry = log.atInfo().setMessage("Idempotency-Key already used").addKeyValue("event", event);
        if (outcome != null) {
            logEntry = logEntry.addKeyValue("outcome", outcome);
        }
        logEntry.log();
    }
}
