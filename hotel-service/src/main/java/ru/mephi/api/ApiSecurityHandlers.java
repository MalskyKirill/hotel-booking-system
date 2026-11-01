package ru.mephi.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import java.util.Map;

public final class ApiSecurityHandlers {
    private ApiSecurityHandlers() { }
    public static AuthenticationEntryPoint unauthorized(ObjectMapper mapper) {
        return (request, response, exception) -> {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(), ApiError.of(HttpStatus.UNAUTHORIZED,
                "UNAUTHORIZED", "Authentication required or token invalid", request.getRequestURI(), Map.of()));
        };
    }
    public static AccessDeniedHandler forbidden(ObjectMapper mapper) {
        return (request, response, exception) -> {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(), ApiError.of(HttpStatus.FORBIDDEN,
                "ACCESS_DENIED", "Access denied", request.getRequestURI(), Map.of()));
        };
    }
}
