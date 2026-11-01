package ru.mephi.api;

import io.swagger.v3.oas.annotations.media.Schema;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import java.time.Instant;
import java.util.Map;

@Schema(requiredProperties = {"timestamp", "status", "error", "code", "message", "path", "fields"})
public record ApiError(@Schema(format = "date-time") String timestamp, int status, String error, String code, String message,
                       String path, Map<String, String> fields) {
    public static ApiError of(HttpStatusCode status, String code, String message, String path, Map<String, String> fields) {
        HttpStatus known = HttpStatus.resolve(status.value());
        return new ApiError(Instant.now().toString(), status.value(), known == null ? "HTTP Error" : known.getReasonPhrase(),
            code, message, path, Map.copyOf(fields));
    }
}
