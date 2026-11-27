package com.abnamro.delivery.order.exception;

/** Pricing could not give a usable answer (down, timed out, overloaded or invalid response). No order is created. */
public class PricingUnavailableException extends RuntimeException {

    private final String reason;

    public PricingUnavailableException(String reason) {
        super("Pricing unavailable: " + reason);
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
