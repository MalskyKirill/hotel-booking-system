package ru.mephi.exception;

public class ReservationConflictException extends RuntimeException {
    public ReservationConflictException(Long roomId, String message) {
        super("Conflict for room " + roomId + ": " + message);
    }
}
