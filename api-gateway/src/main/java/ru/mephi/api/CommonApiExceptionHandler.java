package ru.mephi.api;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.*;
import org.springframework.web.bind.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.client.*;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
@Order(100)
public class CommonApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(CommonApiExceptionHandler.class);
    private ResponseEntity<ApiError> error(HttpStatusCode status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(ApiError.of(status, code, message, request.getRequestURI(), Map.of()));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, String> fields = ex.getBindingResult().getFieldErrors().stream().collect(Collectors.toMap(
            org.springframework.validation.FieldError::getField,
            field -> field.getDefaultMessage() == null ? "Invalid value" : field.getDefaultMessage(), (a, b) -> a));
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(ApiError.of(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
            "Validation failed", request.getRequestURI(), fields));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> invalidBody(HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST_BODY", "Request body is missing or invalid", request);
    }
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> missing(MissingServletRequestParameterException ex, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "MISSING_PARAMETER", "Required parameter '" + ex.getParameterName() + "' is missing", request);
    }
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> invalidParameter(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "Invalid parameter '" + ex.getName() + "'", request);
    }
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> invalidMethod(HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Validation failed", request);
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> status(ResponseStatusException ex, HttpServletRequest request) {
        HttpStatus known = HttpStatus.resolve(ex.getStatusCode().value());
        String code = known == null ? "HTTP_ERROR" : known.name();
        String message = ex.getStatusCode().is5xxServerError() ? "Service unavailable"
            : ex.getReason() == null ? (known == null ? "Request rejected" : known.getReasonPhrase()) : ex.getReason();
        return error(ex.getStatusCode(), code, message, request);
    }
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class,
        HttpRequestMethodNotSupportedException.class, HttpMediaTypeNotSupportedException.class,
        HttpMediaTypeNotAcceptableException.class})
    public ResponseEntity<ApiError> protocol(Exception ex, HttpServletRequest request) {
        ErrorResponse response = (ErrorResponse) ex;
        HttpStatus status = HttpStatus.valueOf(response.getStatusCode().value());
        return ResponseEntity.status(status).headers(response.getHeaders()).contentType(MediaType.APPLICATION_JSON).body(
            ApiError.of(status, status.name(), status.getReasonPhrase(), request.getRequestURI(), Map.of()));
    }
    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<ApiError> remote(RestClientResponseException ex, HttpServletRequest request) {
        return error(ex.getStatusCode(), "UPSTREAM_ERROR", "Upstream request failed", request);
    }
    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<ApiError> unavailable(HttpServletRequest request) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "Upstream service unavailable", request);
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception ex, HttpServletRequest request) {
        // Gateway MVC can wrap transport errors in RuntimeException before MVC advice sees them.
        Throwable cause = ex;
        for (int depth = 0; cause != null && depth < 20; depth++, cause = cause.getCause()) {
            if (cause instanceof SocketException || cause instanceof SocketTimeoutException
                    || cause instanceof UnknownHostException || cause instanceof HttpTimeoutException
                    || cause instanceof ResourceAccessException) {
                return unavailable(request);
            }
        }
        log.error("Unhandled API failure: path={}, type={}", request.getRequestURI(), ex.getClass().getName());
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal server error", request);
    }
}
