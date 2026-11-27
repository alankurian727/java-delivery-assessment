package com.abnamro.delivery.order.service;

import com.abnamro.delivery.order.configuration.IdempotencyProperties;
import com.abnamro.delivery.order.domain.DeliveryOrder;
import com.abnamro.delivery.order.domain.IdempotencyState;
import com.abnamro.delivery.order.domain.OrderRequest;
import com.abnamro.delivery.order.exception.DeliveryRejectedException;
import com.abnamro.delivery.order.exception.IdempotencyConflictException;
import com.abnamro.delivery.order.exception.IdempotencyInProgressException;
import com.abnamro.delivery.order.pricing.PricingDecision;
import com.abnamro.delivery.order.repository.DeliveryOrderEntity;
import com.abnamro.delivery.order.repository.DeliveryOrderRepository;
import com.abnamro.delivery.order.repository.IdempotencyRecordEntity;
import com.abnamro.delivery.order.repository.IdempotencyRecordRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the idempotency record of each request. Every method is one short transaction; the Pricing call happens
 * between them, never inside one. Ownership of a key is decided by the database: the primary key on insert and the
 * version on every update, so it holds across concurrent requests and multiple instances.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyRecordRepository records;
    private final DeliveryOrderRepository orders;
    private final IdempotencyProperties properties;
    private final Clock clock;

    public IdempotencyService(IdempotencyRecordRepository records, DeliveryOrderRepository orders,
                              IdempotencyProperties properties, Clock clock) {
        this.records = records;
        this.orders = orders;
        this.properties = properties;
        this.clock = clock;
    }

    /** The outcome of claiming a key: either this request owns it (at a version) or it already has an order. */
    public record Claim(long version, UUID completedOrderId) {

        public boolean isCompleted() {
            return completedOrderId != null;
        }
    }

    /**
     * Claims the key for this request, or replays the stored outcome of an earlier request with the same key.
     * Rejected outcomes are replayed by throwing the same rejection again.
     */
    @Transactional
    public Claim claim(String key, String fingerprint) {
        Instant now = clock.instant();
        Instant leaseUntil = now.plus(properties.lease());
        if (records.insertIfAbsent(key, fingerprint, leaseUntil, now) == 1) {
            return new Claim(0, null);
        }

        IdempotencyRecordEntity record = records.findById(key).orElseThrow();
        if (!record.getRequestFingerprint().equals(fingerprint)) {
            logEvent("order.idempotency_conflict", null);
            throw new IdempotencyConflictException();
        }
        switch (record.getState()) {
            case COMPLETED -> {
                logEvent("order.replayed", "CONFIRMED");
                return new Claim(record.getVersion(), record.getOrderId());
            }
            case REJECTED -> {
                logEvent("order.replayed", "REJECTED");
                throw new DeliveryRejectedException(record.getRejectionCode());
            }
            case IN_PROGRESS -> {
                if (record.getLeaseUntil().isAfter(now)) {
                    logEvent("order.idempotency_in_progress", null);
                    throw new IdempotencyInProgressException();
                }
            }
            case PRICING_UNAVAILABLE -> {
                // Retryable: fall through and evaluate again with the same key.
            }
        }
        // A retry after PRICING_UNAVAILABLE, or a request whose owner stopped (lease expired): take it over.
        if (records.takeOver(key, record.getVersion(), leaseUntil, now) == 1) {
            return new Claim(record.getVersion() + 1, null);
        }
        throw new IdempotencyInProgressException();
    }

    @Transactional
    public void reject(String key, long version, String rejectionCode) {
        finish(key, version, IdempotencyState.REJECTED, rejectionCode, null);
    }

    @Transactional
    public void markPricingUnavailable(String key, long version) {
        finish(key, version, IdempotencyState.PRICING_UNAVAILABLE, null, null);
    }

    /** Creates the order and completes the record in one transaction, so they can never disagree. */
    @Transactional
    public DeliveryOrder confirm(String key, long version, OrderRequest request, PricingDecision decision) {
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
                clock.instant()));
        // If another request took the key over meanwhile, this throws and the order insert is rolled back.
        finish(key, version, IdempotencyState.COMPLETED, null, order.getId());
        return order.toDomain();
    }

    private void finish(String key, long version, IdempotencyState state, String rejectionCode, UUID orderId) {
        if (records.finish(key, version, state, rejectionCode, orderId, clock.instant()) == 0) {
            throw new IdempotencyInProgressException();
        }
    }

    private static void logEvent(String event, String outcome) {
        var builder = log.atInfo().setMessage("Idempotency-Key already used").addKeyValue("event", event);
        if (outcome != null) {
            builder = builder.addKeyValue("outcome", outcome);
        }
        builder.log();
    }
}
