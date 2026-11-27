package com.abnamro.delivery.order.service;

import com.abnamro.delivery.order.domain.DeliveryOrder;
import com.abnamro.delivery.order.domain.OrderRequest;
import com.abnamro.delivery.order.exception.DeliveryRejectedException;
import com.abnamro.delivery.order.exception.OrderNotFoundException;
import com.abnamro.delivery.order.exception.PricingUnavailableException;
import com.abnamro.delivery.order.pricing.PricingClient;
import com.abnamro.delivery.order.pricing.PricingDecision;
import com.abnamro.delivery.order.repository.DeliveryOrderEntity;
import com.abnamro.delivery.order.repository.DeliveryOrderRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Creates orders following ADR-005: an order is created only when Pricing returns an eligible, valid evaluation.
 * A rejection is recorded on the idempotency record so it can be replayed; when Pricing is unavailable the key is
 * released, so the client can retry with the same key.
 */
@Service
public class DeliveryOrderService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryOrderService.class);
    private static final String UNSUPPORTED_COUNTRY = "UNSUPPORTED_COUNTRY";

    private final IdempotencyService idempotency;
    private final PricingClient pricingClient;
    private final DeliveryOrderRepository orders;
    private final ObjectMapper objectMapper;

    public DeliveryOrderService(IdempotencyService idempotency, PricingClient pricingClient,
                                DeliveryOrderRepository orders, ObjectMapper objectMapper) {
        this.idempotency = idempotency;
        this.pricingClient = pricingClient;
        this.orders = orders;
        this.objectMapper = objectMapper;
    }

    public DeliveryOrder create(String idempotencyKey, OrderRequest request) {
        IdempotencyService.Claim claim = idempotency.claim(idempotencyKey, fingerprint(request));
        if (claim.isCompleted()) {
            return get(claim.completedOrderId());
        }

        if (!request.isWithinNetherlands()) {
            idempotency.reject(idempotencyKey, claim.version(), UNSUPPORTED_COUNTRY);
            logRejected(UNSUPPORTED_COUNTRY, null);
            throw new DeliveryRejectedException(UNSUPPORTED_COUNTRY);
        }

        UUID pricingRequestId = UUID.randomUUID();
        PricingDecision decision;
        try {
            decision = pricingClient.evaluate(pricingRequestId, request);
        } catch (PricingUnavailableException e) {
            idempotency.release(idempotencyKey, claim.version());
            log.atWarn().setMessage("Order not created: Pricing unavailable")
                    .addKeyValue("event", "order.pricing_unavailable")
                    .addKeyValue("pricingRequestId", pricingRequestId)
                    .addKeyValue("reason", e.getReason())
                    .log();
            throw e;
        }

        if (!decision.isEligible()) {
            idempotency.reject(idempotencyKey, claim.version(), decision.rejectionCode());
            logRejected(decision.rejectionCode(), pricingRequestId);
            throw new DeliveryRejectedException(decision.rejectionCode());
        }

        DeliveryOrder order = idempotency.confirm(idempotencyKey, claim.version(), request, decision);
        log.atInfo().setMessage("Order created")
                .addKeyValue("event", "order.created")
                .addKeyValue("orderId", order.id())
                .addKeyValue("pricingRequestId", pricingRequestId)
                .addKeyValue("deliveryType", request.deliveryType())
                .addKeyValue("price", order.price().amount())
                .log();
        return order;
    }

    /** Reads the stored order; the confirmed price never changes, and Pricing is not called. */
    public DeliveryOrder get(UUID orderId) {
        return orders.findById(orderId)
                .map(DeliveryOrderEntity::toDomain)
                .orElseThrow(OrderNotFoundException::new);
    }

    /** SHA-256 of the normalised request, so equivalent requests (for example "1012 ab" and "1012AB") match. */
    private String fingerprint(OrderRequest request) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(request);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Could not fingerprint the request", e);
        }
    }

    private static void logRejected(String rejectionCode, UUID pricingRequestId) {
        var builder = log.atInfo().setMessage("Order not created: delivery rejected")
                .addKeyValue("event", "order.rejected")
                .addKeyValue("rejectionCode", rejectionCode);
        if (pricingRequestId != null) {
            builder = builder.addKeyValue("pricingRequestId", pricingRequestId);
        }
        builder.log();
    }
}
