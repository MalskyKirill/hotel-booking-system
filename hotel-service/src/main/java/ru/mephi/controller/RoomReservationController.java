package ru.mephi.controller;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.mephi.dto.CreateReservationRequest;
import ru.mephi.dto.ReservationResponse;
import ru.mephi.service.RoomReservationService;
import ru.mephi.exception.IdempotencyConflictException;

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
     * Используется для компенсации при сбое Saga. Тело содержит исходные параметры
     * reserve: они позволяют сохранить RELEASED до прихода запоздавшего запроса.
     */
    @PostMapping("/{bookingId}/release")
    public ResponseEntity<ReservationResponse> release(@PathVariable Long bookingId,
                                                       @Valid @RequestBody CreateReservationRequest request) {
        if (!bookingId.equals(request.bookingId())) {
            throw new IdempotencyConflictException(bookingId);
        }
        ReservationResponse response = reservationService.release(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{bookingId}/cancel")
    public ReservationResponse cancel(@PathVariable Long bookingId,
                                       @Valid @RequestBody CreateReservationRequest request) {
        if (!bookingId.equals(request.bookingId())) throw new IdempotencyConflictException(bookingId);
        return reservationService.cancel(request);
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
