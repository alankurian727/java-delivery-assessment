package com.abnamro.delivery.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.abnamro.delivery.order.domain.IdempotencyOutcome;
import com.abnamro.delivery.order.exception.DeliveryRejectedException;
import com.abnamro.delivery.order.exception.IdempotencyConflictException;
import com.abnamro.delivery.order.exception.OutcomeAlreadyRecordedException;
import com.abnamro.delivery.order.repository.IdempotencyRecordEntity;
import com.abnamro.delivery.order.repository.IdempotencyRecordRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdempotencyServiceTest {

    private static final String KEY = "key-1";
    private static final String FINGERPRINT = "fingerprint-1";
    private static final Instant NOW = Instant.parse("2026-11-25T08:30:00Z");

    private final IdempotencyRecordRepository recordRepository = mock(IdempotencyRecordRepository.class);
    private final IdempotencyService service =
            new IdempotencyService(recordRepository, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void newKeyHasNoPreviousOutcome() {
        when(recordRepository.findById(KEY)).thenReturn(Optional.empty());

        assertThat(service.findPreviousOutcome(KEY, FINGERPRINT)).isEmpty();
    }

    @Test
    void confirmedKeyReturnsItsOrder() {
        UUID orderId = UUID.randomUUID();
        IdempotencyRecordEntity confirmed = record(FINGERPRINT, IdempotencyOutcome.CONFIRMED, null, orderId);
        when(recordRepository.findById(KEY)).thenReturn(Optional.of(confirmed));

        assertThat(service.findPreviousOutcome(KEY, FINGERPRINT)).contains(orderId);
    }

    @Test
    void rejectedKeyReturnsTheRejection() {
        IdempotencyRecordEntity rejected =
                record(FINGERPRINT, IdempotencyOutcome.REJECTED, "WEIGHT_LIMIT_EXCEEDED", null);
        when(recordRepository.findById(KEY)).thenReturn(Optional.of(rejected));

        assertThatThrownBy(() -> service.findPreviousOutcome(KEY, FINGERPRINT))
                .isInstanceOf(DeliveryRejectedException.class)
                .extracting("rejectionCode").isEqualTo("WEIGHT_LIMIT_EXCEEDED");
    }

    @Test
    void differentRequestWithSameKeyIsAConflict() {
        IdempotencyRecordEntity otherRequest =
                record("other-fingerprint", IdempotencyOutcome.CONFIRMED, null, UUID.randomUUID());
        when(recordRepository.findById(KEY)).thenReturn(Optional.of(otherRequest));

        assertThatThrownBy(() -> service.findPreviousOutcome(KEY, FINGERPRINT))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void recordsAConfirmation() {
        UUID orderId = UUID.randomUUID();
        when(recordRepository.insertIfAbsent(KEY, FINGERPRINT, "CONFIRMED", null, orderId, NOW)).thenReturn(1);

        service.recordConfirmation(KEY, FINGERPRINT, orderId);

        verify(recordRepository).insertIfAbsent(KEY, FINGERPRINT, "CONFIRMED", null, orderId, NOW);
    }

    @Test
    void recordsARejection() {
        when(recordRepository.insertIfAbsent(KEY, FINGERPRINT, "REJECTED", "UNSUPPORTED_COUNTRY", null, NOW))
                .thenReturn(1);

        service.recordRejection(KEY, FINGERPRINT, "UNSUPPORTED_COUNTRY");

        verify(recordRepository).insertIfAbsent(KEY, FINGERPRINT, "REJECTED", "UNSUPPORTED_COUNTRY", null, NOW);
    }

    @Test
    void keyAlreadyRecordedByAnotherRequestIsReported() {
        when(recordRepository.insertIfAbsent(eq(KEY), eq(FINGERPRINT), eq("CONFIRMED"), isNull(), any(), any()))
                .thenReturn(0);

        assertThatThrownBy(() -> service.recordConfirmation(KEY, FINGERPRINT, UUID.randomUUID()))
                .isInstanceOf(OutcomeAlreadyRecordedException.class);
    }

    private static IdempotencyRecordEntity record(String fingerprint, IdempotencyOutcome outcome,
                                                  String rejectionCode, UUID orderId) {
        IdempotencyRecordEntity record = mock(IdempotencyRecordEntity.class);
        when(record.getRequestFingerprint()).thenReturn(fingerprint);
        when(record.getOutcome()).thenReturn(outcome);
        when(record.getRejectionCode()).thenReturn(rejectionCode);
        when(record.getOrderId()).thenReturn(orderId);
        return record;
    }
}
