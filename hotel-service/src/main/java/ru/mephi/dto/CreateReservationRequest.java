package ru.mephi.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;

public record CreateReservationRequest(
    @NotNull @Positive Long bookingId,
    @NotNull @Positive Long roomId,
    @NotNull LocalDate startDate,
    @NotNull LocalDate endDate
) {
    @AssertTrue(message = "startDate must be before endDate")
    public boolean isDateRangeValid() {
        if (startDate == null || endDate == null) return true;
        return startDate.isBefore(endDate);
    }
}
