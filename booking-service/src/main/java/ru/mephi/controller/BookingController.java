package ru.mephi.controller;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.mephi.dto.BookingResponse;
import ru.mephi.dto.CreateBookingRequest;
import ru.mephi.service.BookingService;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping
    public ResponseEntity<BookingResponse> create(
        @Valid @RequestBody CreateBookingRequest request,
        @AuthenticationPrincipal Jwt jwt) {

        String username = jwt.getSubject();
        BookingResponse response = bookingService.create(request, username);

        return ResponseEntity.ok(response);
    }
}
