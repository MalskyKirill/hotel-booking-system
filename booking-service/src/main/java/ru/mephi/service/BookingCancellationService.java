package ru.mephi.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import ru.mephi.dto.BookingResponse;
import ru.mephi.model.BookingStatus;

@Service
public class BookingCancellationService {
    private final BookingSagaTransactions state;
    private final BookingSagaCoordinator saga;
    public BookingCancellationService(BookingSagaTransactions state, BookingSagaCoordinator saga) {
        this.state = state; this.saga = saga;
    }
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public BookingResponse cancel(Long id, String username) {
        BookingResponse booking = state.requestCancellation(id, username);
        return booking.status() == BookingStatus.CANCELLED ? booking : saga.resume(id);
    }
}
