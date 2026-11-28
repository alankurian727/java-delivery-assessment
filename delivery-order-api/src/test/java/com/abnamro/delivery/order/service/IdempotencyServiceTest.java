package com.abnamro.delivery.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    void newKeyIsBoundAndEvaluated() {
        when(recordRepository.bindIfAbsent(KEY, FINGERPRINT, NOW)).thenReturn(1);

        assertThat(service.bindOrFindOutcome(KEY, FINGERPRINT)).isEmpty();
        verify(recordRepository).bindIfAbsent(KEY, FINGERPRINT, NOW);
    }

    @Test
    void sameRequestOnABoundUndecidedKeyIsEvaluatedAgain() {
        // For example after Pricing was unavailable, or after a crash before the decision.
        bound(record(FINGERPRINT, null, null, null));

        assertThat(service.bindOrFindOutcome(KEY, FINGERPRINT)).isEmpty();
    }

    @Test
    void differentRequestOnABoundUndecidedKeyIsAConflict() {
        bound(record("other-fingerprint", null, null, null));

        assertThatThrownBy(() -> service.bindOrFindOutcome(KEY, FINGERPRINT)).isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void confirmedKeyReturnsItsOrder() {
        UUID orderId = UUID.randomUUID();
        bound(record(FINGERPRINT, IdempotencyOutcome.CONFIRMED, null, orderId));

        assertThat(service.bindOrFindOutcome(KEY, FINGERPRINT)).contains(orderId);
    }

    @Test
    void rejectedKeyReturnsTheRejection() {
        bound(record(FINGERPRINT, IdempotencyOutcome.REJECTED, "WEIGHT_LIMIT_EXCEEDED", null));

        assertThatThrownBy(() -> service.bindOrFindOutcome(KEY, FINGERPRINT))
                .isInstanceOf(DeliveryRejectedException.class)
                .extracting("rejectionCode").isEqualTo("WEIGHT_LIMIT_EXCEEDED");
    }

    @Test
    void differentRequestOnADecidedKeyIsAConflict() {
        bound(record("other-fingerprint", IdempotencyOutcome.CONFIRMED, null, UUID.randomUUID()));

        assertThatThrownBy(() -> service.bindOrFindOutcome(KEY, FINGERPRINT)).isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void findingAnOutcomeDoesNotBindTheKey() {
        UUID orderId = UUID.randomUUID();
        IdempotencyRecordEntity record = record(FINGERPRINT, IdempotencyOutcome.CONFIRMED, null, orderId);
        when(recordRepository.findById(KEY)).thenReturn(Optional.of(record));

        assertThat(service.findOutcome(KEY, FINGERPRINT)).contains(orderId);
        verify(recordRepository, never()).bindIfAbsent(any(), any(), any());
    }

    @Test
    void recordsAConfirmation() {
        UUID orderId = UUID.randomUUID();
        when(recordRepository.recordOutcome(KEY, "CONFIRMED", null, orderId)).thenReturn(1);

        service.recordConfirmation(KEY, orderId);

        verify(recordRepository).recordOutcome(KEY, "CONFIRMED", null, orderId);
    }

    @Test
    void recordsARejection() {
        when(recordRepository.recordOutcome(KEY, "REJECTED", "UNSUPPORTED_COUNTRY", null)).thenReturn(1);

        service.recordRejection(KEY, "UNSUPPORTED_COUNTRY");

        verify(recordRepository).recordOutcome(KEY, "REJECTED", "UNSUPPORTED_COUNTRY", null);
    }

    @Test
    void keyAlreadyDecidedByAnotherRequestIsReported() {
        UUID orderId = UUID.randomUUID();
        when(recordRepository.recordOutcome(KEY, "CONFIRMED", null, orderId)).thenReturn(0);

        assertThatThrownBy(() -> service.recordConfirmation(KEY, orderId))
                .isInstanceOf(OutcomeAlreadyRecordedException.class);
    }

    private void bound(IdempotencyRecordEntity record) {
        when(recordRepository.bindIfAbsent(KEY, FINGERPRINT, NOW)).thenReturn(0);
        when(recordRepository.findById(KEY)).thenReturn(Optional.of(record));
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
