package com.abnamro.delivery.order.service;

import static com.abnamro.delivery.order.fixture.TestOrders.orderRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.abnamro.delivery.order.domain.DeliveryOrder;
import com.abnamro.delivery.order.domain.DeliveryType;
import com.abnamro.delivery.order.domain.Money;
import com.abnamro.delivery.order.domain.OrderRequest;
import com.abnamro.delivery.order.domain.Party;
import com.abnamro.delivery.order.domain.PostalCode;
import com.abnamro.delivery.order.exception.DeliveryRejectedException;
import com.abnamro.delivery.order.exception.OrderNotFoundException;
import com.abnamro.delivery.order.exception.OutcomeAlreadyRecordedException;
import com.abnamro.delivery.order.exception.PricingUnavailableException;
import com.abnamro.delivery.order.pricing.PricingClient;
import com.abnamro.delivery.order.pricing.PricingDecision;
import com.abnamro.delivery.order.repository.DeliveryOrderEntity;
import com.abnamro.delivery.order.repository.DeliveryOrderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class DeliveryOrderServiceTest {

    private static final String KEY = "key-1";
    private static final Instant NOW = Instant.parse("2026-11-25T08:30:00Z");
    private static final Money PRICE = new Money(new BigDecimal("8.95"), "EUR");

    private final IdempotencyService idempotencyService = mock(IdempotencyService.class);
    private final PricingClient pricingClient = mock(PricingClient.class);
    private final DeliveryOrderRepository orderRepository = mock(DeliveryOrderRepository.class);
    private final DeliveryOrderService service = new DeliveryOrderService(idempotencyService, pricingClient,
            orderRepository, new TransactionTemplate(mock(PlatformTransactionManager.class)), new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void createsAnOrderWhenPricingSaysEligible() {
        when(pricingClient.evaluate(any(), any())).thenAnswer(call -> PricingDecision.eligible(call.getArgument(0), PRICE));
        when(orderRepository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

        DeliveryOrder order = service.createOrder(KEY, orderRequest());

        assertThat(order.price()).isEqualTo(PRICE);
        assertThat(order.createdAt()).isEqualTo(NOW);
        verify(idempotencyService).recordConfirmation(eq(KEY), anyString(), eq(order.id()));
    }

    @Test
    void replaysAnExistingOrderWithoutCallingPricing() {
        UUID orderId = UUID.randomUUID();
        when(idempotencyService.replayOutcome(eq(KEY), anyString())).thenReturn(Optional.of(orderId));
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(storedOrder(orderId)));

        DeliveryOrder order = service.createOrder(KEY, orderRequest());

        assertThat(order.id()).isEqualTo(orderId);
        verifyNoInteractions(pricingClient);
    }

    @Test
    void rejectsAnUnsupportedCountryWithoutCallingPricing() {
        assertThatThrownBy(() -> service.createOrder(KEY, orderRequest("BE")))
                .isInstanceOf(DeliveryRejectedException.class)
                .extracting("rejectionCode").isEqualTo("UNSUPPORTED_COUNTRY");

        verify(idempotencyService).recordRejection(eq(KEY), anyString(), eq("UNSUPPORTED_COUNTRY"));
        verifyNoInteractions(pricingClient);
    }

    @Test
    void recordsARejectionFromPricing() {
        when(pricingClient.evaluate(any(), any()))
                .thenAnswer(call -> PricingDecision.rejected(call.getArgument(0), "WEIGHT_LIMIT_EXCEEDED"));

        assertThatThrownBy(() -> service.createOrder(KEY, orderRequest()))
                .isInstanceOf(DeliveryRejectedException.class)
                .extracting("rejectionCode").isEqualTo("WEIGHT_LIMIT_EXCEEDED");

        verify(idempotencyService).recordRejection(eq(KEY), anyString(), eq("WEIGHT_LIMIT_EXCEEDED"));
        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    void recordsNothingWhenPricingIsUnavailable() {
        when(pricingClient.evaluate(any(), any())).thenThrow(new PricingUnavailableException("timeout"));

        assertThatThrownBy(() -> service.createOrder(KEY, orderRequest()))
                .isInstanceOf(PricingUnavailableException.class);

        verify(idempotencyService, never()).recordRejection(any(), any(), any());
        verify(idempotencyService, never()).recordConfirmation(any(), any(), any());
        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    void answersWithTheWinnersOrderWhenAConcurrentRequestRecordedTheKeyFirst() {
        UUID winnerOrderId = UUID.randomUUID();
        when(idempotencyService.replayOutcome(eq(KEY), anyString()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winnerOrderId));
        when(pricingClient.evaluate(any(), any())).thenAnswer(call -> PricingDecision.eligible(call.getArgument(0), PRICE));
        when(orderRepository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        doThrow(new OutcomeAlreadyRecordedException())
                .when(idempotencyService).recordConfirmation(eq(KEY), anyString(), any());
        when(orderRepository.findById(winnerOrderId)).thenReturn(Optional.of(storedOrder(winnerOrderId)));

        DeliveryOrder order = service.createOrder(KEY, orderRequest());

        assertThat(order.id()).isEqualTo(winnerOrderId);
    }

    @Test
    void equivalentRequestsHaveTheSameFingerprintAndDifferentOnesDoNot() {
        when(pricingClient.evaluate(any(), any())).thenThrow(new PricingUnavailableException("timeout"));
        OrderRequest sameAgain = orderRequest();
        OrderRequest heavier = new OrderRequest("CUSTOMER-TEST-001",
                new Party("Test Sender", PostalCode.of("1012 ab"), "NL"),
                new Party("Test Recipient", PostalCode.of("5611EM"), "NL"), 3000, DeliveryType.NEXT_DAY);

        ArgumentCaptor<String> fingerprints = ArgumentCaptor.forClass(String.class);
        for (OrderRequest request : new OrderRequest[] {orderRequest(), sameAgain, heavier}) {
            assertThatThrownBy(() -> service.createOrder(KEY, request)).isInstanceOf(PricingUnavailableException.class);
        }
        verify(idempotencyService, times(3)).replayOutcome(eq(KEY), fingerprints.capture());

        assertThat(fingerprints.getAllValues().get(0))
                .hasSize(64)
                .isEqualTo(fingerprints.getAllValues().get(1))
                .isNotEqualTo(fingerprints.getAllValues().get(2));
    }

    @Test
    void unknownOrderIsNotFound() {
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.retrieveOrder(orderId)).isInstanceOf(OrderNotFoundException.class);
    }

    private static DeliveryOrderEntity storedOrder(UUID orderId) {
        return new DeliveryOrderEntity(orderId, "CUSTOMER-TEST-001", "Test Sender", "1012AB", "NL",
                "Test Recipient", "5611EM", "NL", 2750, DeliveryType.NEXT_DAY,
                PRICE.amount(), "EUR", UUID.randomUUID(), NOW);
    }
}
