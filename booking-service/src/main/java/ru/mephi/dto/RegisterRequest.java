package ru.mephi.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.AssertTrue;
import java.nio.charset.StandardCharsets;

public record RegisterRequest(
    @NotBlank(message = "Username must not be blank")
    @Size(max = 100, message = "Username must not exceed 100 characters")
    String username,

    @NotBlank(message = "Password must not be blank")
    @Size(min = 8, max = 72, message = "Password must contain 8 to 72 characters")
    @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY, description = "8–72 символа, максимум 72 UTF-8 байта")
    String password
) {
    @AssertTrue(message = "Password must not exceed 72 UTF-8 bytes")
    @Schema(hidden = true)
    public boolean isPasswordLengthSupported() {
        return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
    public RegisterRequest {
        if (username != null) {
            username = username.trim();
        }
    }
}
