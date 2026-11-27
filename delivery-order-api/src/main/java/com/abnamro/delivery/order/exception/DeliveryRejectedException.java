package com.abnamro.delivery.order.exception;

/** The delivery is not eligible (for example weight limit, same-day route or country). No order is created. */
public class DeliveryRejectedException extends RuntimeException {

    private final String rejectionCode;

    public DeliveryRejectedException(String rejectionCode) {
        super("Delivery rejected: " + rejectionCode);
        this.rejectionCode = rejectionCode;
    }

    public String getRejectionCode() {
        return rejectionCode;
    }
}
