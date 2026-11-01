package ru.mephi.controller;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import ru.mephi.dto.*;
import ru.mephi.service.*;
import java.net.URI;

@RestController
@RequestMapping("/api/admin/rooms")
@PreAuthorize("hasRole('ADMIN')")
public class RoomAdminController {
    private final RoomAdminService admin;
    private final RoomService rooms;
    public RoomAdminController(RoomAdminService admin, RoomService rooms) { this.admin = admin; this.rooms = rooms; }
    @GetMapping public RoomPage list(@RequestParam(required = false) Long hotelId,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return admin.list(hotelId, page, size);
    }
    @GetMapping("/{id}") public RoomResponse get(@PathVariable Long id) { return admin.get(id); }
    @PostMapping public ResponseEntity<RoomResponse> create(@Valid @RequestBody CreateRoomRequest request) {
        var result = rooms.create(request);
        return ResponseEntity.created(URI.create("/api/admin/rooms/" + result.getId())).body(result);
    }
    @PutMapping("/{id}") public RoomResponse update(@PathVariable Long id, @Valid @RequestBody UpdateRoomRequest request) {
        return admin.update(id, request);
    }
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@PathVariable Long id) {
        admin.delete(id); return ResponseEntity.noContent().build();
    }
}
