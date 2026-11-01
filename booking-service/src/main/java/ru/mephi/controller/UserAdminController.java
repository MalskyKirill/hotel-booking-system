package ru.mephi.controller;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import ru.mephi.dto.*;
import ru.mephi.service.UserAdminService;
import java.net.URI;

@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserAdminController {
    private final UserAdminService users;
    public UserAdminController(UserAdminService users) { this.users = users; }
    @GetMapping public UserPage list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return users.list(page, size);
    }
    @GetMapping("/{id}") public UserResponse get(@PathVariable Long id) { return users.get(id); }
    @ApiResponse(responseCode = "201", description = "Ресурс создан")
    @PostMapping public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        var result = users.create(request);
        return ResponseEntity.created(URI.create("/api/admin/users/" + result.id())).body(result);
    }
    @PatchMapping("/{id}") public UserResponse update(@PathVariable Long id, @Valid @RequestBody UpdateUserRequest request) {
        return users.update(id, request);
    }
    @ApiResponse(responseCode = "204", description = "Ресурс удалён", content = @Content)
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@PathVariable Long id) {
        users.delete(id); return ResponseEntity.noContent().build();
    }
}
