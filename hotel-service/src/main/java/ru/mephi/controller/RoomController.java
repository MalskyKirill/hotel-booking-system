package ru.mephi.controller;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.mephi.dto.CreateRoomRequest;
import ru.mephi.dto.RoomResponse;
import ru.mephi.service.RoomService;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/rooms")
public class RoomController {

    private final RoomService roomService;

    public RoomController(RoomService roomService) {
        this.roomService = roomService;
    }

    @PostMapping
    public ResponseEntity<RoomResponse> createRoom(@Valid @RequestBody CreateRoomRequest req) {
        RoomResponse res = roomService.create(req);

        return ResponseEntity.created(URI.create("/api/rooms/" + res.getId()))
            .body(res);
    }

    @GetMapping
    public List<RoomResponse> getRooms(@RequestParam(required = false) Long hotelId) {
        return roomService.getAvailableRooms(hotelId);
    }


}
