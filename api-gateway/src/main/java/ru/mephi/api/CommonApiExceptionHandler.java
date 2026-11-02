package ru.mephi.api;

import jakarta.servlet.http.HttpServletRequest;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;

@RestControllerAdvice
@Order(100)
public class CommonApiExceptionHandler extends AbstractCommonApiExceptionHandler {
  @Override
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> unexpected(Exception ex, HttpServletRequest request) {
    // Gateway MVC can wrap transport errors in RuntimeException before MVC advice sees them.
    Throwable cause = ex;
    for (int depth = 0; cause != null && depth < 20; depth++, cause = cause.getCause()) {
      if (cause instanceof SocketException
          || cause instanceof SocketTimeoutException
          || cause instanceof UnknownHostException
          || cause instanceof HttpTimeoutException
          || cause instanceof ResourceAccessException) {
        return unavailable(request);
      }
    }
    return super.unexpected(ex, request);
  }
}
