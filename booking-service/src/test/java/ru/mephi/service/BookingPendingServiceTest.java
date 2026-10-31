package ru.mephi.service;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import ru.mephi.dto.BookingResponse;
import ru.mephi.dto.CreateBookingRequest;
import ru.mephi.exception.IdempotencyConflictException;
import ru.mephi.model.BookingStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookingPendingServiceTest {

    private final BookingCreationTransactions transactions = mock(BookingCreationTransactions.class);
    private final BookingPendingService service = new BookingPendingService(transactions);
    private final CreateBookingRequest request = new CreateBookingRequest(1L,
        LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 2), "same-request");

    @Test
    void readsWinnerAfterCreationTransactionFails() {
        BookingResponse winner = new BookingResponse(1L, request.roomId(), request.startDate(),
            request.endDate(), BookingStatus.PENDING, Instant.now());
        when(transactions.create(request, "alice"))
            .thenThrow(new DataIntegrityViolationException("Duplicate requestId"));
        when(transactions.findExisting(request, "alice")).thenReturn(Optional.of(winner));

        assertEquals(winner, service.create(request, "alice"));
        var order = inOrder(transactions);
        order.verify(transactions).create(request, "alice");
        order.verify(transactions).findExisting(request, "alice");
    }

    @Test
    void doesNotHideConstraintFailureWithoutExistingBooking() {
        DataIntegrityViolationException failure = new DataIntegrityViolationException("Other constraint");
        when(transactions.create(request, "alice")).thenThrow(failure);
        when(transactions.findExisting(request, "alice")).thenReturn(Optional.empty());

        assertSame(failure, assertThrows(DataIntegrityViolationException.class,
            () -> service.create(request, "alice")));
    }

    @Test
    void conflictingPayloadAfterRaceRemainsConflict() {
        when(transactions.create(request, "alice"))
            .thenThrow(new DataIntegrityViolationException("Duplicate requestId"));
        when(transactions.findExisting(request, "alice"))
            .thenThrow(new IdempotencyConflictException(request.requestId()));

        assertThrows(IdempotencyConflictException.class, () -> service.create(request, "alice"));
    }
}
