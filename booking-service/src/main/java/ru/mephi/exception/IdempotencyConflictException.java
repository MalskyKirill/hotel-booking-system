package ru.mephi.exception;

public class IdempotencyConflictException extends RuntimeException {

    private final String requestId;

    public IdempotencyConflictException(String requestId) {
        super("Request with idempotency key '" + requestId
            + "' was already used with different parameters");
        this.requestId = requestId;
    }

    public String getRequestId() {
        return requestId;
    }
}
