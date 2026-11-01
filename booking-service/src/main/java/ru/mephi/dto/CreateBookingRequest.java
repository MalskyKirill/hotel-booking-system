package ru.mephi.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;

public record CreateBookingRequest(

    @Positive(message = "roomId must be positive")
    @Schema(description = "Обязателен при ручном выборе; игнорируется при autoSelect=true")
    Long roomId,

    @NotNull(message = "startDate must not be null")
    LocalDate startDate,

    @NotNull(message = "endDate must not be null")
    @Schema(description = "Дата выезда, не входит в интервал; должна быть позже startDate")
    LocalDate endDate,

    @NotBlank(message = "requestId must not be blank")
    @Schema(description = "Ключ идемпотентности. Повтор с другими параметрами или владельцем даёт 409")
    String requestId,
    @Schema(defaultValue = "false", description = "Автоподбор свободного номера по timesBooked, затем id")
    boolean autoSelect,
    @Schema(description = "Необязательный фильтр отеля при автоподборе; игнорируется при ручном выборе")
    @Positive Long hotelId

) {
    public CreateBookingRequest(Long roomId, LocalDate startDate, LocalDate endDate, String requestId) {
        this(roomId, startDate, endDate, requestId, false, null);
    }
    public CreateBookingRequest {
        if (autoSelect) roomId = null;
        else hotelId = null;
    }
    @AssertTrue(message = "roomId is required unless autoSelect=true")
    @Schema(hidden = true)
    public boolean isRoomSelectionValid() { return autoSelect || roomId != null; }

    @AssertTrue(message = "startDate must be before endDate")
    @Schema(hidden = true)
    public boolean isDateRangeValid() {
        if (startDate == null || endDate == null) {
            return true;
        }
        return startDate.isBefore(endDate);
    }
}
