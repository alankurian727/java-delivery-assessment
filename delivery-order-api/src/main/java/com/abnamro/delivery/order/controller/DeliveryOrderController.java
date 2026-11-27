package com.abnamro.delivery.order.controller;

import com.abnamro.delivery.order.api.DeliveryOrdersApi;
import com.abnamro.delivery.order.api.model.ConfirmedOrder;
import com.abnamro.delivery.order.api.model.CreateDeliveryOrderRequest;
import com.abnamro.delivery.order.domain.DeliveryOrder;
import com.abnamro.delivery.order.domain.OrderRequest;
import com.abnamro.delivery.order.service.DeliveryOrderService;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DeliveryOrderController implements DeliveryOrdersApi {

    private final DeliveryOrderService deliveryOrderService;
    private final DeliveryOrderApiMapper mapper;

    public DeliveryOrderController(DeliveryOrderService deliveryOrderService, DeliveryOrderApiMapper mapper) {
        this.deliveryOrderService = deliveryOrderService;
        this.mapper = mapper;
    }

    @Override
    public ResponseEntity<ConfirmedOrder> createDeliveryOrder(String idempotencyKey,
                                                              CreateDeliveryOrderRequest createDeliveryOrderRequest,
                                                              String xCorrelationID) {
        OrderRequest orderRequest = mapper.toOrderRequest(createDeliveryOrderRequest);
        DeliveryOrder order = deliveryOrderService.createOrder(idempotencyKey, orderRequest);
        return ResponseEntity.created(URI.create("/api/v1/delivery-orders/" + order.id()))
                .body(mapper.toConfirmedOrder(order));
    }

    @Override
    public ResponseEntity<ConfirmedOrder> getDeliveryOrder(UUID orderId, String xCorrelationID) {
        return ResponseEntity.ok(mapper.toConfirmedOrder(deliveryOrderService.retrieveOrder(orderId)));
    }
}
