package com.abnamro.delivery.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.abnamro.delivery.order.domain.DeliveryType;
import com.abnamro.delivery.order.domain.OrderRequest;
import com.abnamro.delivery.order.domain.Party;
import com.abnamro.delivery.order.domain.PostalCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class RequestFingerprintTest {

    private final RequestFingerprint fingerprint = new RequestFingerprint(new ObjectMapper());

    @Test
    void isASha256HexString() {
        assertThat(fingerprint.compute(request("1012AB", "NL", 2750))).matches("[0-9a-f]{64}");
    }

    @Test
    void equivalentRequestsHaveTheSameFingerprint() {
        // Postal code spacing and case, and country case, are normalised by the domain before hashing.
        assertThat(fingerprint.compute(request("1012 ab", "nl", 2750)))
                .isEqualTo(fingerprint.compute(request("1012AB", "NL", 2750)));
    }

    @Test
    void differentRequestsHaveDifferentFingerprints() {
        assertThat(fingerprint.compute(request("1012AB", "NL", 3000)))
                .isNotEqualTo(fingerprint.compute(request("1012AB", "NL", 2750)));
    }

    private static OrderRequest request(String senderPostalCode, String senderCountry, long weightInGrams) {
        return new OrderRequest("CUSTOMER-TEST-001",
                new Party("Test Sender", PostalCode.of(senderPostalCode), senderCountry),
                new Party("Test Recipient", PostalCode.of("5611EM"), "NL"),
                weightInGrams, DeliveryType.NEXT_DAY);
    }
}
