package com.abnamro.delivery.order.exception;

public class IdempotencyInProgressException extends RuntimeException {

    public IdempotencyInProgressException() {
        super("A request with this Idempotency-Key is still being processed.");
    }
}
