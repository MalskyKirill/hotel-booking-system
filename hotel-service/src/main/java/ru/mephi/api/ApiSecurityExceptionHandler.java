package ru.mephi.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestControllerAdvice
@Order(50)
public class ApiSecurityExceptionHandler {
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> forbidden(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON).body(ApiError.of(HttpStatus.FORBIDDEN,
            "ACCESS_DENIED", "Access denied", request.getRequestURI(), Map.of()));
    }
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> unauthorized(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
            .contentType(MediaType.APPLICATION_JSON).body(ApiError.of(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required or token invalid",
                request.getRequestURI(), Map.of()));
    }
}
