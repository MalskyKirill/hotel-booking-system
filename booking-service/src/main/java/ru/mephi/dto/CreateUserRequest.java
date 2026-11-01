package ru.mephi.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.*;
import ru.mephi.model.Role;
import java.nio.charset.StandardCharsets;

public record CreateUserRequest(@NotBlank @Size(max = 100) String username,
    @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY, description = "8–72 символа, максимум 72 UTF-8 байта")
    @NotBlank @Size(min = 8, max = 72) String password, @NotNull Role role) {
    public CreateUserRequest { if (username != null) username = username.trim(); }
    @AssertTrue(message = "Password must not exceed 72 UTF-8 bytes")
    @Schema(hidden = true)
    public boolean isPasswordLengthSupported() { return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72; }
}
