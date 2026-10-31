package ru.mephi.exception;

public class InvalidReservationStateException extends RuntimeException {
    public InvalidReservationStateException(Long bookingId, String message) {
        super("Invalid state for booking " + bookingId + ": " + message);
    }
}
