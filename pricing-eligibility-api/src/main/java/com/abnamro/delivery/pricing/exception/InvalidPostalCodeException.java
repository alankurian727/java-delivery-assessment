package com.abnamro.delivery.pricing.exception;

/**
 * A postal code that is not a valid Dutch postal code. Returned to the client as HTTP 400 without this
 * message; any other exception is treated as an internal error.
 */
public class InvalidPostalCodeException extends RuntimeException {

    public InvalidPostalCodeException() {
        super("Invalid Dutch postal code");
    }
}
