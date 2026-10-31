package ru.mephi.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.mephi.dto.BookingResponse;
import ru.mephi.dto.CreateBookingRequest;
import ru.mephi.model.BookingStatus;

@Service
public class BookingService {
    private final BookingPendingService pendingService;
    private final BookingSagaCoordinator saga;

    public BookingService(BookingPendingService pendingService, BookingSagaCoordinator saga) {
        this.pendingService = pendingService;
        this.saga = saga;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public BookingResponse create(CreateBookingRequest request, String username) {
        BookingResponse booking = pendingService.create(request, username);
        return booking.status() == BookingStatus.PENDING ? saga.resume(booking.id()) : booking;
    }
}
