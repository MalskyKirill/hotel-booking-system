package ru.mephi.exception;

public class RemoteReservationNotFoundException extends RuntimeException {
    private final Long bookingId;

    public RemoteReservationNotFoundException(Long bookingId) {
        super("Reservation not found for booking " + bookingId);
        this.bookingId = bookingId;
    }

    public Long getBookingId() {
        return bookingId;
    }
}
