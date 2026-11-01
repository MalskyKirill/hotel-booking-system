package ru.mephi.exception;

public class BookingNotFoundException extends RuntimeException {
    public BookingNotFoundException() { super("Booking not found"); }
}
