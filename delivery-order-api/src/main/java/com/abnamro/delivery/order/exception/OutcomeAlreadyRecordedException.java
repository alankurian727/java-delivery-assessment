package com.abnamro.delivery.order.exception;

/** Another request with the same Idempotency-Key recorded its outcome first. Handled internally by returning that outcome. */
public class OutcomeAlreadyRecordedException extends RuntimeException {

    public OutcomeAlreadyRecordedException() {
        super("An outcome is already recorded for this Idempotency-Key.");
    }
}
