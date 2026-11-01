package ru.mephi.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDate;

public record AutoReservationRequest(@NotNull @Positive Long bookingId, @Positive Long hotelId,
                                     @NotNull LocalDate startDate, @NotNull LocalDate endDate) {
    @AssertTrue(message = "startDate must be before endDate")
    public boolean isDateRangeValid() {
        return startDate == null || endDate == null || startDate.isBefore(endDate);
    }
}
