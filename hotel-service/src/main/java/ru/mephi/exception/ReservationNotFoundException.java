package ru.mephi.exception;

public class ReservationNotFoundException extends RuntimeException {
    public ReservationNotFoundException(Long bookingId) {
        super("Reservation for booking " + bookingId + " not found");
    }
}
