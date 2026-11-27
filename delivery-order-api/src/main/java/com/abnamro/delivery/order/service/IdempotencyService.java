package com.abnamro.delivery.order.service;

import com.abnamro.delivery.order.domain.DeliveryOrder;
import com.abnamro.delivery.order.domain.IdempotencyOutcome;
import com.abnamro.delivery.order.domain.OrderRequest;
import com.abnamro.delivery.order.exception.DeliveryRejectedException;
import com.abnamro.delivery.order.exception.IdempotencyConflictException;
import com.abnamro.delivery.order.exception.OutcomeAlreadyRecordedException;
import com.abnamro.delivery.order.pricing.PricingDecision;
import com.abnamro.delivery.order.repository.DeliveryOrderEntity;
import com.abnamro.delivery.order.repository.DeliveryOrderRepository;
import com.abnamro.delivery.order.repository.IdempotencyRecordEntity;
import com.abnamro.delivery.order.repository.IdempotencyRecordRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores and replays the final outcome of each Idempotency-Key (ADR-008). Nothing is written before the outcome is
 * known, so there is no in-progress state to recover. Each method is one short transaction; the Pricing call never
 * runs inside one.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyRecordRepository records;
    private final DeliveryOrderRepository orders;
    private final Clock clock;

    public IdempotencyService(IdempotencyRecordRepository records, DeliveryOrderRepository orders, Clock clock) {
        this.records = records;
        this.orders = orders;
        this.clock = clock;
    }

    /**
     * Returns the order ID stored for this key, or empty when the key is new. A stored rejection is replayed by
     * throwing it again; a different request under the same key is a conflict.
     */
    @Transactional(readOnly = true)
    public Optional<UUID> replay(String key, String fingerprint) {
        Optional<IdempotencyRecordEntity> found = records.findById(key);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        IdempotencyRecordEntity record = found.get();
        if (!record.getRequestFingerprint().equals(fingerprint)) {
            logEvent("order.idempotency_conflict", null);
            throw new IdempotencyConflictException();
        }
        logEvent("order.replayed", record.getOutcome());
        if (record.getOutcome() == IdempotencyOutcome.REJECTED) {
            throw new DeliveryRejectedException(record.getRejectionCode());
        }
        return Optional.of(record.getOrderId());
    }

    @Transactional
    public void recordRejection(String key, String fingerprint, String rejectionCode) {
        insert(key, fingerprint, IdempotencyOutcome.REJECTED, rejectionCode, null);
    }

    /**
     * Creates the order and records it under the key in one transaction. If another request recorded this key
     * first, the exception rolls the order back, so a key never has more than one order.
     */
    @Transactional
    public DeliveryOrder confirm(String key, String fingerprint, OrderRequest request, PricingDecision decision) {
        DeliveryOrderEntity order = orders.saveAndFlush(new DeliveryOrderEntity(
                UUID.randomUUID(),
                request.customerReference(),
                request.sender().name(),
                request.sender().postalCode().value(),
                request.sender().countryCode(),
                request.recipient().name(),
                request.recipient().postalCode().value(),
                request.recipient().countryCode(),
                request.weightInGrams(),
                request.deliveryType(),
                decision.price().amount(),
                decision.price().currency(),
                decision.pricingRequestId(),
                // PostgreSQL stores microseconds; truncate so a replayed response is identical to the first one.
                clock.instant().truncatedTo(ChronoUnit.MICROS)));
        insert(key, fingerprint, IdempotencyOutcome.CONFIRMED, null, order.getId());
        return order.toDomain();
    }

    private void insert(String key, String fingerprint, IdempotencyOutcome outcome, String rejectionCode,
                        UUID orderId) {
        if (records.insertIfAbsent(key, fingerprint, outcome.name(), rejectionCode, orderId, clock.instant()) == 0) {
            throw new OutcomeAlreadyRecordedException();
        }
    }

    private static void logEvent(String event, IdempotencyOutcome outcome) {
        var builder = log.atInfo().setMessage("Idempotency-Key already used").addKeyValue("event", event);
        if (outcome != null) {
            builder = builder.addKeyValue("outcome", outcome);
        }
        builder.log();
    }
}
