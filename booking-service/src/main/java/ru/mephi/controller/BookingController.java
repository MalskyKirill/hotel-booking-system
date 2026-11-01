package ru.mephi.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

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
    public BookingPage history(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
                               @RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "20") int size) {
        return queries.history(jwt.getSubject(), page, size);
    }

    @GetMapping("/{id}")
    public BookingResponse get(@PathVariable Long id, @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        return queries.get(id, jwt.getSubject());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Отменить собственное бронирование", description = "Повторная отмена идемпотентна.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Отмена завершена", content = @Content(mediaType = "application/json", schema = @Schema(implementation = BookingResponse.class))),
        @ApiResponse(responseCode = "202", description = "Намерение отмены сохранено; ожидается восстановление", content = @Content(mediaType = "application/json", schema = @Schema(implementation = BookingResponse.class)))
    })
    public ResponseEntity<BookingResponse> cancel(@PathVariable Long id, @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        BookingResponse result = cancellations.cancel(id, jwt.getSubject());
        return ResponseEntity.status(result.status() == BookingStatus.CANCELLED
            ? HttpStatus.OK : HttpStatus.ACCEPTED).body(result);
    }

    @PostMapping
    @Operation(summary = "Забронировать номер", description = "Ручной выбор или autoSelect. Повтор requestId с теми же параметрами возвращает ту же операцию.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Бронирование подтверждено в обоих сервисах", content = @Content(mediaType = "application/json", schema = @Schema(implementation = BookingResponse.class))),
        @ApiResponse(responseCode = "202", description = "Операция сохранена и ожидает восстановления", content = @Content(mediaType = "application/json", schema = @Schema(implementation = BookingResponse.class))),
        @ApiResponse(responseCode = "409", description = "BOOKING_CANCELLED: ID в fields.bookingId; либо IDEMPOTENCY_CONFLICT", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<?> create(
        @Valid @RequestBody CreateBookingRequest request,
        @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
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
