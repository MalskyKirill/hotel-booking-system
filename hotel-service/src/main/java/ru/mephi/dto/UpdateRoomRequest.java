package ru.mephi.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record UpdateRoomRequest(@NotBlank String roomNumber, @NotNull @Positive Integer capacity,
    @NotNull @Positive @Digits(integer = 8, fraction = 2) BigDecimal price, @NotNull Boolean available) { }
