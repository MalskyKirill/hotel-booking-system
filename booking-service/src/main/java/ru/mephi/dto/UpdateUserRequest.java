package ru.mephi.dto;

import jakarta.validation.constraints.*;
import ru.mephi.model.Role;
import java.nio.charset.StandardCharsets;

public record UpdateUserRequest(@Size(min = 8, max = 72) String password, Role role) {
    @AssertTrue(message = "At least one field must be supplied")
    public boolean isUpdatePresent() { return password != null || role != null; }
    @AssertTrue(message = "Password must not be blank")
    public boolean isPasswordNotBlank() { return password == null || !password.isBlank(); }
    @AssertTrue(message = "Password must not exceed 72 UTF-8 bytes")
    public boolean isPasswordLengthSupported() { return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72; }
}
