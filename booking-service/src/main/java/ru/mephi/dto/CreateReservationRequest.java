package ru.mephi.dto;

import java.time.LocalDate;

public record CreateReservationRequest(
    Long bookingId,
    Long roomId,
    LocalDate startDate,
    LocalDate endDate
) {
}
