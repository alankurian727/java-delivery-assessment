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

    private final IdempotencyRecordRepository recordRepository;
    private final DeliveryOrderRepository orderRepository;
    private final Clock clock;

    public IdempotencyService(IdempotencyRecordRepository recordRepository, DeliveryOrderRepository orderRepository,
                              Clock clock) {
        this.recordRepository = recordRepository;
        this.orderRepository = orderRepository;
        this.clock = clock;
    }

    /**
     * Returns the order ID stored for this key, or empty when the key is new. A stored rejection is replayed by
     * throwing it again; a different request under the same key is a conflict.
     */
    @Transactional(readOnly = true)
    public Optional<UUID> replayOutcome(String key, String fingerprint) {
        Optional<IdempotencyRecordEntity> existing = recordRepository.findById(key);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        IdempotencyRecordEntity record = existing.get();
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
        recordOutcome(key, fingerprint, IdempotencyOutcome.REJECTED, rejectionCode, null);
    }

    /**
     * Creates the order and records it under the key in one transaction. If another request recorded this key
     * first, the exception rolls the order back, so a key never has more than one order.
     */
    @Transactional
    public DeliveryOrder confirmOrder(String key, String fingerprint, OrderRequest request,
                                      PricingDecision pricingDecision) {
        DeliveryOrderEntity orderEntity = orderRepository.saveAndFlush(new DeliveryOrderEntity(
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
                pricingDecision.price().amount(),
                pricingDecision.price().currency(),
                pricingDecision.pricingRequestId(),
                // PostgreSQL stores microseconds; truncate so a replayed response is identical to the first one.
                clock.instant().truncatedTo(ChronoUnit.MICROS)));
        recordOutcome(key, fingerprint, IdempotencyOutcome.CONFIRMED, null, orderEntity.getId());
        return orderEntity.toDomain();
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
