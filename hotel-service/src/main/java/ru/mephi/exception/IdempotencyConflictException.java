package ru.mephi.exception;

public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(Long bookingId) {
        super("Booking with id " + bookingId + " already exists with different parameters");
    }
}
