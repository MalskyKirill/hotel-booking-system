package ru.mephi.controller;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.mephi.dto.CreateReservationRequest;
import ru.mephi.dto.ReservationResponse;
import ru.mephi.service.RoomReservationService;

@RestController
@RequestMapping("/internal/reservations")
public class RoomReservationController {

    private final RoomReservationService reservationService;

    public RoomReservationController(RoomReservationService reservationService) {
        this.reservationService = reservationService;
    }

    /**
     * Создать резервирование (HELD).
     * Вызывается из Booking Service в рамках Saga.
     */
    @PostMapping
    public ResponseEntity<ReservationResponse> create(@Valid @RequestBody CreateReservationRequest request) {
        ReservationResponse response = reservationService.reserve(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Подтвердить резервирование (HELD -> CONFIRMED).
     * Идемпотентно: повторный вызов безопасен.
     */
    @PostMapping("/{bookingId}/confirm")
    public ResponseEntity<ReservationResponse> confirm(@PathVariable Long bookingId) {
        ReservationResponse response = reservationService.confirm(bookingId);
        return ResponseEntity.ok(response);
    }

    /**
     * Освободить резервирование (HELD -> RELEASED).
     * Используется для компенсации при сбое Saga.
     */
    @PostMapping("/{bookingId}/release")
    public ResponseEntity<ReservationResponse> release(@PathVariable Long bookingId) {
        ReservationResponse response = reservationService.release(bookingId);
        return ResponseEntity.ok(response);
    }

    /**
     * Получить текущее состояние резервирования.
     * Используется для разрешения неопределенности (reconciliation).
     */
    @GetMapping("/{bookingId}")
    public ResponseEntity<ReservationResponse> getStatus(@PathVariable Long bookingId) {
        ReservationResponse response = reservationService.getByBookingId(bookingId);
        return ResponseEntity.ok(response);
    }
}
