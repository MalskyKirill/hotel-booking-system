package ru.mephi.exception.handler;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.mephi.exception.HotelHasRoomException;
import ru.mephi.exception.HotelNotFoundException;
import ru.mephi.exception.RoomAlreadyExistsException;
import ru.mephi.exception.RoomNotFoundException;
import ru.mephi.exception.ReservationNotFoundException;
import ru.mephi.exception.ReservationConflictException;
import ru.mephi.exception.RoomUnavailableException;
import ru.mephi.exception.IdempotencyConflictException;
import ru.mephi.exception.InvalidReservationStateException;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;


@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ru.mephi.exception.NoAvailableRoomException.class)
    public ResponseEntity<Map<String, Object>> handleNoAvailableRoom(ru.mephi.exception.NoAvailableRoomException ex) {
        return reservationError(HttpStatus.CONFLICT, "NO_AVAILABLE_ROOM", ex);
    }

    @ExceptionHandler(RoomNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleRoomNotFound(RoomNotFoundException ex) {
        return reservationError(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND", ex);
    }

    @ExceptionHandler(ReservationNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleReservationNotFound(ReservationNotFoundException ex) {
        return reservationError(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND", ex);
    }

    @ExceptionHandler(ReservationConflictException.class)
    public ResponseEntity<Map<String, Object>> handleReservationConflict(ReservationConflictException ex) {
        return reservationError(HttpStatus.CONFLICT, "RESERVATION_CONFLICT", ex);
    }

    @ExceptionHandler(RoomUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleRoomUnavailable(RoomUnavailableException ex) {
        return reservationError(HttpStatus.CONFLICT, "ROOM_UNAVAILABLE", ex);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<Map<String, Object>> handleReservationIdempotencyConflict(IdempotencyConflictException ex) {
        return reservationError(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", ex);
    }

    @ExceptionHandler(InvalidReservationStateException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidReservationState(InvalidReservationStateException ex) {
        return reservationError(HttpStatus.CONFLICT, "INVALID_RESERVATION_STATE", ex);
    }

    private ResponseEntity<Map<String, Object>> reservationError(HttpStatus status, String code,
                                                               RuntimeException ex) {
        return ResponseEntity.status(status).body(Map.of(
            "timestamp", Instant.now().toString(),
            "status", status.value(),
            "error", status.getReasonPhrase(),
            "code", code,
            "message", ex.getMessage()
        ));
    }

    @ExceptionHandler(HotelNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(HotelNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", 404,
                "error", "Not Found",
                "message", ex.getMessage(),
                "id", ex.getId()
                ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
            .collect(Collectors.toMap(
                FieldError::getField,
                fe -> fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "invalid value"
                , (a, b) -> a
            ));

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", 400,
                "error", "Bad request",
                "message", "Validation failed",
                "fields", fieldErrors
            ));
    }

    @ExceptionHandler(HotelHasRoomException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(HotelHasRoomException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", 409,
                "error", "Conflict",
                "message", ex.getMessage(),
                "id", ex.getId()
            ));
    }

    @ExceptionHandler(RoomAlreadyExistsException.class)
    public ResponseEntity<Map<String, Object>> handleRoomAlreadyExists(RoomAlreadyExistsException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", 409,
                "error", "Conflict",
                "message", ex.getMessage(),
                "hotelId", ex.getHotelId(),
                "roomNumber", ex.getRoomNumber(),
                "source", "precheck"   // ← полезно для отладки/метрик
            ));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrity(DataIntegrityViolationException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", 409,
                "error", "Conflict",
                "message", "Room with this number already exists in this hotel",
                "source", "database"   // ← видно, что сработал UNIQUE, а не предпроверка
            ));
    }


}
