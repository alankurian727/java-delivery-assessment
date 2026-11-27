package com.abnamro.delivery.order.service;

import com.abnamro.delivery.order.domain.DeliveryOrder;
import com.abnamro.delivery.order.domain.OrderRequest;
import com.abnamro.delivery.order.exception.DeliveryRejectedException;
import com.abnamro.delivery.order.exception.OrderNotFoundException;
import com.abnamro.delivery.order.exception.OutcomeAlreadyRecordedException;
import com.abnamro.delivery.order.exception.PricingUnavailableException;
import com.abnamro.delivery.order.pricing.PricingClient;
import com.abnamro.delivery.order.pricing.PricingDecision;
import com.abnamro.delivery.order.repository.DeliveryOrderEntity;
import com.abnamro.delivery.order.repository.DeliveryOrderRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates orders following ADR-005: an order is created only when Pricing returns an eligible, valid evaluation.
 * The outcome is recorded once per Idempotency-Key and replayed for repeated requests (ADR-008); when Pricing is
 * unavailable nothing is recorded, so the client can retry with the same key.
 */
@Service
public class DeliveryOrderService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryOrderService.class);
    private static final String UNSUPPORTED_COUNTRY = "UNSUPPORTED_COUNTRY";

    private final IdempotencyService idempotencyService;
    private final PricingClient pricingClient;
    private final DeliveryOrderRepository orderRepository;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public DeliveryOrderService(IdempotencyService idempotencyService, PricingClient pricingClient,
                                DeliveryOrderRepository orderRepository, TransactionTemplate transactionTemplate,
                                ObjectMapper objectMapper, Clock clock) {
        this.idempotencyService = idempotencyService;
        this.pricingClient = pricingClient;
        this.orderRepository = orderRepository;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public DeliveryOrder createOrder(String idempotencyKey, OrderRequest request) {
        String fingerprint = computeFingerprint(request);
        Optional<UUID> existingOrderId = idempotencyService.replayOutcome(idempotencyKey, fingerprint);
        if (existingOrderId.isPresent()) {
            return retrieveOrder(existingOrderId.get());
        }
        try {
            return evaluateNewOrder(idempotencyKey, fingerprint, request);
        } catch (OutcomeAlreadyRecordedException e) {
            // A concurrent request with the same key recorded its outcome first: answer with that outcome.
            return idempotencyService.replayOutcome(idempotencyKey, fingerprint)
                    .map(this::retrieveOrder)
                    .orElseThrow(() -> new IllegalStateException("Recorded outcome not found"));
        }
    }

    private DeliveryOrder evaluateNewOrder(String idempotencyKey, String fingerprint, OrderRequest request) {
        if (!request.isWithinNetherlands()) {
            idempotencyService.recordRejection(idempotencyKey, fingerprint, UNSUPPORTED_COUNTRY);
            logRejection(UNSUPPORTED_COUNTRY, null);
            throw new DeliveryRejectedException(UNSUPPORTED_COUNTRY);
        }

        UUID pricingRequestId = UUID.randomUUID();
        PricingDecision pricingDecision;
        try {
            pricingDecision = pricingClient.evaluate(pricingRequestId, request);
        } catch (PricingUnavailableException e) {
            // Nothing was recorded, so a retry with the same key is evaluated as a new request.
            log.atWarn().setMessage("Order not created: Pricing unavailable")
                    .addKeyValue("event", "order.pricing_unavailable")
                    .addKeyValue("pricingRequestId", pricingRequestId)
                    .addKeyValue("reason", e.getReason())
                    .log();
            throw e;
        }

        if (!pricingDecision.isEligible()) {
            idempotencyService.recordRejection(idempotencyKey, fingerprint, pricingDecision.rejectionCode());
            logRejection(pricingDecision.rejectionCode(), pricingRequestId);
            throw new DeliveryRejectedException(pricingDecision.rejectionCode());
        }

        DeliveryOrder order = confirmOrder(idempotencyKey, fingerprint, request, pricingDecision);
        log.atInfo().setMessage("Order created")
                .addKeyValue("event", "order.created")
                .addKeyValue("orderId", order.id())
                .addKeyValue("pricingRequestId", pricingRequestId)
                .addKeyValue("deliveryType", request.deliveryType())
                .addKeyValue("price", order.price().amount())
                .log();
        return order;
    }

    /**
     * Saves the order and records it under the key in one transaction. If another request recorded this key first,
     * the exception rolls the order back, so a key never has more than one order (ADR-008).
     */
    private DeliveryOrder confirmOrder(String idempotencyKey, String fingerprint, OrderRequest request,
                                       PricingDecision pricingDecision) {
        return transactionTemplate.execute(status -> {
            // Flushed now: the idempotency record references the order by foreign key.
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
            idempotencyService.recordConfirmation(idempotencyKey, fingerprint, orderEntity.getId());
            return orderEntity.toDomain();
        });
    }

    /** Reads the stored order; the confirmed price never changes, and Pricing is not called. */
    public DeliveryOrder retrieveOrder(UUID orderId) {
        return orderRepository.findById(orderId)
                .map(DeliveryOrderEntity::toDomain)
                .orElseThrow(OrderNotFoundException::new);
    }

    /** SHA-256 of the normalised request, so equivalent requests (for example "1012 ab" and "1012AB") match. */
    private String computeFingerprint(OrderRequest request) {
        try {
            byte[] requestJson = objectMapper.writeValueAsBytes(request);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(requestJson));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Could not fingerprint the request", e);
        }
    }

    private static void logRejection(String rejectionCode, UUID pricingRequestId) {
        var logEntry = log.atInfo().setMessage("Order not created: delivery rejected")
                .addKeyValue("event", "order.rejected")
                .addKeyValue("rejectionCode", rejectionCode);
        if (pricingRequestId != null) {
            logEntry = logEntry.addKeyValue("pricingRequestId", pricingRequestId);
        }
        logEntry.log();
    }
}
