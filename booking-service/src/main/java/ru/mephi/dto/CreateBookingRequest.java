package ru.mephi.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;

public record CreateBookingRequest(

    @NotNull(message = "roomId must not be null")
    @Positive(message = "roomId must be positive")
    Long roomId,

    @NotNull(message = "startDate must not be null")
    LocalDate startDate,

    @NotNull(message = "endDate must not be null")
    LocalDate endDate,

    @NotBlank(message = "requestId must not be blank")
    String requestId

) {
    @AssertTrue(message = "startDate must be before endDate")
    public boolean isDateRangeValid() {
        if (startDate == null || endDate == null) {
            return true;
        }
        return startDate.isBefore(endDate);
    }
}
