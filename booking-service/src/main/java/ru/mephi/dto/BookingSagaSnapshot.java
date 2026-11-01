package ru.mephi.dto;

public record BookingSagaSnapshot(BookingResponse booking, String requestId, boolean compensating,
                                  boolean autoSelect, Long hotelId) {
    public BookingSagaSnapshot(BookingResponse booking, String requestId, boolean compensating) {
        this(booking, requestId, compensating, false, null);
    }
}
