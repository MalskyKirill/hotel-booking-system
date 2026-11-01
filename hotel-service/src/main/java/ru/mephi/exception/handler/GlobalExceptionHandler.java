package ru.mephi.exception.handler;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import ru.mephi.api.ApiError;
import ru.mephi.exception.*;
import java.util.Map;

@RestControllerAdvice
@Order(0)
public class GlobalExceptionHandler {
    @ExceptionHandler(NoAvailableRoomException.class)
    public ResponseEntity<ApiError> handleNoAvailableRoomException(NoAvailableRoomException ex, HttpServletRequest request) {
        return error(409, "NO_AVAILABLE_ROOM", ex.getMessage(), request);
    }
    @ExceptionHandler(RoomNotFoundException.class)
    public ResponseEntity<ApiError> handleRoomNotFoundException(RoomNotFoundException ex, HttpServletRequest request) {
        return error(404, "ROOM_NOT_FOUND", ex.getMessage(), request);
    }
    @ExceptionHandler(ReservationNotFoundException.class)
    public ResponseEntity<ApiError> handleReservationNotFoundException(ReservationNotFoundException ex, HttpServletRequest request) {
        return error(404, "RESERVATION_NOT_FOUND", ex.getMessage(), request);
    }
    @ExceptionHandler(ReservationConflictException.class)
    public ResponseEntity<ApiError> handleReservationConflictException(ReservationConflictException ex, HttpServletRequest request) {
        return error(409, "RESERVATION_CONFLICT", ex.getMessage(), request);
    }
    @ExceptionHandler(RoomUnavailableException.class)
    public ResponseEntity<ApiError> handleRoomUnavailableException(RoomUnavailableException ex, HttpServletRequest request) {
        return error(409, "ROOM_UNAVAILABLE", ex.getMessage(), request);
    }
    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ApiError> handleIdempotencyConflictException(IdempotencyConflictException ex, HttpServletRequest request) {
        return error(409, "IDEMPOTENCY_CONFLICT", ex.getMessage(), request);
    }
    @ExceptionHandler(InvalidReservationStateException.class)
    public ResponseEntity<ApiError> handleInvalidReservationStateException(InvalidReservationStateException ex, HttpServletRequest request) {
        return error(409, "INVALID_RESERVATION_STATE", ex.getMessage(), request);
    }
    @ExceptionHandler(HotelNotFoundException.class)
    public ResponseEntity<ApiError> handleHotelNotFoundException(HotelNotFoundException ex, HttpServletRequest request) {
        return error(404, "HOTEL_NOT_FOUND", ex.getMessage(), request);
    }
    @ExceptionHandler(HotelHasRoomException.class)
    public ResponseEntity<ApiError> handleHotelHasRoomException(HotelHasRoomException ex, HttpServletRequest request) {
        return error(409, "HOTEL_HAS_ROOMS", ex.getMessage(), request);
    }
    @ExceptionHandler(RoomAlreadyExistsException.class)
    public ResponseEntity<ApiError> handleRoomAlreadyExistsException(RoomAlreadyExistsException ex, HttpServletRequest request) {
        return error(409, "ROOM_ALREADY_EXISTS", ex.getMessage(), request);
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> integrity(HttpServletRequest request) {
        return error(409, "DATA_INTEGRITY_CONFLICT", "Data integrity constraint violated", request);
    }
    private ResponseEntity<ApiError> error(int status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(ApiError.of(HttpStatusCode.valueOf(status), code, message,
            request.getRequestURI(), Map.of()));
    }
}
