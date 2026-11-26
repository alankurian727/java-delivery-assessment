package com.abnamro.delivery.pricing.domain;

/**
 * A request value that passed the API contract but is rejected by the domain. Returned to the client
 * as HTTP 400 without this message; any other exception is treated as an internal error.
 */
public class DomainValidationException extends RuntimeException {

    public DomainValidationException(String message) {
        super(message);
    }
}
