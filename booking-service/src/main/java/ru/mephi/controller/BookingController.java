package ru.mephi.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import ru.mephi.api.ApiError;
import java.util.Map;
import ru.mephi.service.BookingCancellationService;
import ru.mephi.model.BookingStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.mephi.dto.BookingResponse;
import ru.mephi.dto.CreateBookingRequest;
import ru.mephi.service.BookingService;
import ru.mephi.service.BookingQueryService;
import ru.mephi.dto.BookingPage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private final BookingService bookingService;
    private final BookingQueryService queries;
    private final BookingCancellationService cancellations;

    public BookingController(BookingService bookingService, BookingQueryService queries,
                             BookingCancellationService cancellations) {
        this.bookingService = bookingService;
        this.queries = queries;
        this.cancellations = cancellations;
    }

    @GetMapping
    public BookingPage history(@AuthenticationPrincipal Jwt jwt,
                               @RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "20") int size) {
        return queries.history(jwt.getSubject(), page, size);
    }

    @GetMapping("/{id}")
    public BookingResponse get(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return queries.get(id, jwt.getSubject());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<BookingResponse> cancel(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        BookingResponse result = cancellations.cancel(id, jwt.getSubject());
        return ResponseEntity.status(result.status() == BookingStatus.CANCELLED
            ? HttpStatus.OK : HttpStatus.ACCEPTED).body(result);
    }

    @PostMapping
    public ResponseEntity<?> create(
        @Valid @RequestBody CreateBookingRequest request,
        @AuthenticationPrincipal Jwt jwt,
        HttpServletRequest servletRequest) {

        String username = jwt.getSubject();
        BookingResponse response = bookingService.create(request, username);

        if (response.status() == BookingStatus.CANCELLED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                .body(ApiError.of(HttpStatus.CONFLICT, "BOOKING_CANCELLED", "Booking was cancelled",
                    servletRequest.getRequestURI(), Map.of("bookingId", response.id().toString(), "bookingStatus", "CANCELLED")));
        }
        HttpStatus status = response.cancellationRequested() ? HttpStatus.ACCEPTED : switch (response.status()) {
            case CONFIRMED -> HttpStatus.OK;
            case PENDING -> HttpStatus.ACCEPTED;
            case CANCELLED -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status).body(response);
    }
}
