package com.abnamro.delivery.order.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.abnamro.delivery.order.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** The Flyway schema refuses inconsistent rows on its own, whatever code writes them. */
class SchemaConstraintsIT extends IntegrationTest {

    private static final String INSERT_ORDER = """
            INSERT INTO delivery_order (id, customer_reference, sender_name, sender_postal_code, sender_country_code,
                recipient_name, recipient_postal_code, recipient_country_code, weight_in_grams, delivery_type, status,
                price_amount, price_currency, pricing_request_id, created_at)
            VALUES (?, 'CUSTOMER-TEST-001', 'Test Sender', '1012AB', 'NL', 'Test Recipient', '5611EM', 'NL',
                ?, 'NEXT_DAY', 'CONFIRMED', ?, 'EUR', gen_random_uuid(), now())
            """;
    private static final String INSERT_RECORD = """
            INSERT INTO idempotency_record (idempotency_key, request_fingerprint, outcome, rejection_code, order_id,
                created_at)
            VALUES (?, 'fingerprint', ?, ?, ?, now())
            """;

    @Test
    void refusesASecondOutcomeForTheSameKey() {
        jdbc.update(INSERT_RECORD, "key-1", "REJECTED", "WEIGHT_LIMIT_EXCEEDED", null);

        assertRefused(() -> jdbc.update(INSERT_RECORD, "key-1", "REJECTED", "WEIGHT_LIMIT_EXCEEDED", null),
                "idempotency_record_pkey");
    }

    @Test
    void refusesAConfirmedRecordWithoutAnOrder() {
        assertRefused(() -> jdbc.update(INSERT_RECORD, "key-1", "CONFIRMED", null, null), "confirmed_has_order");
    }

    @Test
    void refusesARejectedRecordWithoutAReasonCode() {
        assertRefused(() -> jdbc.update(INSERT_RECORD, "key-1", "REJECTED", null, null), "rejected_has_reason_code");
    }

    @Test
    void refusesARecordForAnOrderThatDoesNotExist() {
        assertRefused(() -> jdbc.update(INSERT_RECORD, "key-1", "CONFIRMED", null, UUID.randomUUID()),
                "idempotency_record_order_id_fkey");
    }

    @Test
    void refusesInvalidOrderValues() {
        assertRefused(() -> jdbc.update(INSERT_ORDER, UUID.randomUUID(), 0, 8.95), "positive_weight");
        assertRefused(() -> jdbc.update(INSERT_ORDER, UUID.randomUUID(), 2750, -1), "non_negative_price");
    }

    private static void assertRefused(Runnable insert, String constraint) {
        assertThatThrownBy(insert::run)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }
}
