package com.abnamro.delivery.order.exception;

public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException() {
        super("The Idempotency-Key was already used for a different request.");
    }
}
