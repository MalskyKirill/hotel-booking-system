package ru.mephi.exception.handler;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import ru.mephi.api.ApiError;
import ru.mephi.exception.*;
import java.util.Map;
import org.springframework.security.authentication.BadCredentialsException;

@RestControllerAdvice
@Order(0)
public class GlobalExceptionHandler {
    @ExceptionHandler(BookingNotFoundException.class)
    public ResponseEntity<ApiError> handleBookingNotFoundException(BookingNotFoundException ex, HttpServletRequest request) {
        return error(404, "BOOKING_NOT_FOUND", "Booking not found", request);
    }
    @ExceptionHandler(UsernameAlreadyExistsException.class)
    public ResponseEntity<ApiError> handleUsernameAlreadyExistsException(UsernameAlreadyExistsException ex, HttpServletRequest request) {
        return error(409, "USERNAME_ALREADY_EXISTS", ex.getMessage(), request);
    }
    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ApiError> handleIdempotencyConflictException(IdempotencyConflictException ex, HttpServletRequest request) {
        return error(409, "IDEMPOTENCY_CONFLICT", ex.getMessage(), request);
    }
    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ApiError> handleUserNotFoundException(UserNotFoundException ex, HttpServletRequest request) {
        return error(401, "USER_NOT_FOUND", "Token refers to a user that no longer exists", request);
    }
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiError> handleBadCredentialsException(BadCredentialsException ex, HttpServletRequest request) {
        return error(401, "INVALID_CREDENTIALS", "Invalid credentials", request);
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> integrity(HttpServletRequest request) {
        return error(409, "DATA_INTEGRITY_CONFLICT", "Data integrity constraint violated", request);
    }
    private ResponseEntity<ApiError> error(int status, String code, String message, HttpServletRequest request) {
        var response = ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON);
        if (status == 401) response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return response.body(ApiError.of(HttpStatusCode.valueOf(status), code, message, request.getRequestURI(), Map.of()));
    }
}
