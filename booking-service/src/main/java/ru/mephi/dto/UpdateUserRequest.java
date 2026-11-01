package ru.mephi.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.*;
import ru.mephi.model.Role;
import java.nio.charset.StandardCharsets;

public record UpdateUserRequest(
    @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY, description = "8–72 символа, максимум 72 UTF-8 байта")
    @Size(min = 8, max = 72) String password, Role role) {
    @AssertTrue(message = "At least one field must be supplied")
    @Schema(hidden = true)
    public boolean isUpdatePresent() { return password != null || role != null; }
    @AssertTrue(message = "Password must not be blank")
    @Schema(hidden = true)
    public boolean isPasswordNotBlank() { return password == null || !password.isBlank(); }
    @AssertTrue(message = "Password must not exceed 72 UTF-8 bytes")
    @Schema(hidden = true)
    public boolean isPasswordLengthSupported() { return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72; }
}
