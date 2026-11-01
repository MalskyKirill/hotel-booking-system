package ru.mephi.api;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public class ApiErrorController implements ErrorController {
    @RequestMapping("/error")
    public ResponseEntity<ApiError> error(HttpServletRequest request) {
        Object value = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = value instanceof Integer number ? number : 500;
        HttpStatus known = HttpStatus.resolve(status);
        if (known == null || status < 400) { status = 500; known = HttpStatus.INTERNAL_SERVER_ERROR; }
        Object original = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        String path = original instanceof String uri ? uri : request.getRequestURI();
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(ApiError.of(known, status == 500 ? "INTERNAL_ERROR" : known.name(),
            status >= 500 ? "Internal server error" : known.getReasonPhrase(), path, Map.of()));
    }
}
