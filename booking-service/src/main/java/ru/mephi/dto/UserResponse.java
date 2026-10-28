package ru.mephi.dto;

import ru.mephi.model.Role;

public record UserResponse(
    Long id,
    String username,
    Role role
) {}
