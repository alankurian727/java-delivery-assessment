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
 * Binds each Idempotency-Key to the request it was first received with, records its outcome once, and returns that
 * outcome for repeated requests (ADR-0010). A bound key without an outcome does not block anything, so there is no
 * in-progress state, lease or take-over. Recording the outcome joins the caller's transaction when there is one.
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
     * Binds the key to this request's fingerprint when it is first seen, in its own short transaction, so concurrent
     * and later requests see the binding before Pricing is called. Returns the stored order ID when the key already
     * has a confirmed outcome, or empty when this request should be evaluated (new key, or bound but not decided yet,
     * for example after Pricing was unavailable). A stored rejection is returned by throwing it again; a different
     * request under the same key is a conflict, decided or not.
     */
    @Transactional
    public Optional<UUID> bindOrFindOutcome(String key, String fingerprint) {
        if (recordRepository.bindIfAbsent(key, fingerprint, clock.instant()) == 1) {
            return Optional.empty();
        }
        IdempotencyRecordEntity record = recordRepository.findById(key)
                .orElseThrow(() -> new IllegalStateException("Bound idempotency record not found"));
        if (!record.getRequestFingerprint().equals(fingerprint)) {
            logEvent("order.idempotency_conflict", null);
            throw new IdempotencyConflictException();
        }
        if (record.getOutcome() == null) {
            return Optional.empty();
        }
        logEvent("order.previous_outcome_returned", record.getOutcome());
        if (record.getOutcome() == IdempotencyOutcome.REJECTED) {
            throw new DeliveryRejectedException(record.getRejectionCode());
        }
        return Optional.of(record.getOrderId());
    }

    @Transactional
    public void recordRejection(String key, String rejectionCode) {
        recordOutcome(key, IdempotencyOutcome.REJECTED, rejectionCode, null);
    }

    /** Throws {@link OutcomeAlreadyRecordedException} when another request decided this key first. */
    @Transactional
    public void recordConfirmation(String key, UUID orderId) {
        recordOutcome(key, IdempotencyOutcome.CONFIRMED, null, orderId);
    }

    private void recordOutcome(String key, IdempotencyOutcome outcome, String rejectionCode, UUID orderId) {
        if (recordRepository.recordOutcome(key, outcome.name(), rejectionCode, orderId) == 0) {
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
