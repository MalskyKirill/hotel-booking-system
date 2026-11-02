package ru.mephi.dto;

import java.time.LocalDate;
import ru.mephi.model.ReservationStatus;

public record ReservationResponse(
    Long bookingId,
    Long roomId,
    LocalDate startDate,
    LocalDate endDate,
    ReservationStatus status) {}
