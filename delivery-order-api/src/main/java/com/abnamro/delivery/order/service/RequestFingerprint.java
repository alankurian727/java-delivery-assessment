package com.abnamro.delivery.order.service;

import com.abnamro.delivery.order.domain.OrderRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * SHA-256 of the normalised request, so equivalent requests (for example "1012 ab" and "1012AB") match.
 * The JSON follows the field order of OrderRequest: reordering its fields changes stored fingerprints.
 */
@Component
public class RequestFingerprint {

    private final ObjectMapper objectMapper;

    public RequestFingerprint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String compute(OrderRequest request) {
        try {
            byte[] requestJson = objectMapper.writeValueAsBytes(request);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(requestJson));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Could not fingerprint the request", e);
        }
    }
}
