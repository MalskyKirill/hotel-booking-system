package ru.mephi.dto;

public record ReservationResponse(
    Long bookingId,
    Long roomId,
    java.time.LocalDate startDate,
    java.time.LocalDate endDate,
    ReservationStatus status // см. ниже
) {
}
