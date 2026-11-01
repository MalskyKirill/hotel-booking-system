package ru.mephi.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import ru.mephi.dto.*;
import ru.mephi.service.AutoReservationService;

@RestController
@RequestMapping("/internal/auto-reservations")
public class AutoReservationController {
    private final AutoReservationService service;
    public AutoReservationController(AutoReservationService service) { this.service = service; }
    @PostMapping public ReservationResponse reserve(@Valid @RequestBody AutoReservationRequest request) {
        return service.reserve(request);
    }
    @PostMapping("/confirm") public ReservationResponse confirm(@Valid @RequestBody AutoReservationRequest request) {
        return service.confirm(request);
    }
    @PostMapping("/release") public ReservationResponse release(@Valid @RequestBody AutoReservationRequest request) {
        return service.release(request);
    }
    @PostMapping("/status") public ReservationResponse status(@Valid @RequestBody AutoReservationRequest request) {
        return service.getStatus(request);
    }
}
