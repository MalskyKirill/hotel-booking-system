package ru.mephi.dto;

import ru.mephi.model.ReservationStatus;

import java.time.LocalDate;

public record ReservationResponse(
    Long bookingId,
    Long roomId,
    LocalDate startDate,
    LocalDate endDate,
    ReservationStatus status
) {}
