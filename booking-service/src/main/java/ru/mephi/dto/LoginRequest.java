package ru.mephi.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
    @NotBlank(message = "Username must not be blank")
    String username,

    @NotBlank(message = "Password must not be blank")
    @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY)
    String password
) {
    public LoginRequest {
        if (username != null) {
            username = username.trim();
        }
    }
}
