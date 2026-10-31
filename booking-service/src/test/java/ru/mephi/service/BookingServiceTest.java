package ru.mephi.service;

import org.junit.jupiter.api.Test;
import ru.mephi.client.HotelReservationClient;
import ru.mephi.dto.*;
import ru.mephi.exception.*;
import ru.mephi.model.BookingStatus;
import java.time.Instant;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookingServiceTest {
    private final BookingSagaTransactions state = mock(BookingSagaTransactions.class);
    private final BookingCreationTransactions transactions = mock(BookingCreationTransactions.class);
    private final HotelReservationClient hotel = mock(HotelReservationClient.class);
    private final BookingSagaCoordinator saga = new BookingSagaCoordinator(state, transactions, hotel, new HotelCallRetry(3, 0));
    private final CreateReservationRequest request = new CreateReservationRequest(1L, 2L,
        LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 3));

    @Test
    void reservesBeforeConfirmingBothServices() {
        pending(false);
        when(hotel.reserve(request)).thenReturn(remote(ReservationStatus.HELD));
        when(hotel.confirm(request)).thenReturn(remote(ReservationStatus.CONFIRMED));
        when(transactions.confirm(1L)).thenReturn(booking(BookingStatus.CONFIRMED));
        assertEquals(BookingStatus.CONFIRMED, saga.resume(1L).status());
        var order = inOrder(hotel, transactions);
        order.verify(hotel).reserve(request);
        order.verify(hotel).confirm(request);
        order.verify(transactions).confirm(1L);
    }

    @Test
    void terminalBookingDoesNotCallHotel() {
        for (BookingStatus status : new BookingStatus[]{BookingStatus.CONFIRMED, BookingStatus.CANCELLED}) {
            when(state.read(1L)).thenReturn(new BookingSagaSnapshot(booking(status), "key", false));
            assertEquals(status, saga.resume(1L).status());
        }
        verifyNoInteractions(hotel, transactions);
    }

    @Test
    void localCommitFailureIsDeferredWithoutCompensatingConfirmedRemote() {
        pending(false);
        when(hotel.reserve(request)).thenReturn(remote(ReservationStatus.CONFIRMED));
        when(transactions.confirm(1L)).thenThrow(new org.springframework.dao.DataIntegrityViolationException("DB failure"));
        when(state.defer(1L)).thenReturn(booking(BookingStatus.PENDING));
        assertEquals(BookingStatus.PENDING, saga.resume(1L).status());
        verify(hotel, never()).release(any());
        verify(state).defer(1L);
    }

    @Test
    void uncertainRemoteCannotBeDeclaredCancelled() {
        pending(true);
        when(hotel.getStatus(request)).thenThrow(new RemoteServiceUncertainException("offline"));
        when(state.defer(1L)).thenReturn(booking(BookingStatus.PENDING));
        assertEquals(BookingStatus.PENDING, saga.resume(1L).status());
        verify(hotel, times(3)).getStatus(request);
        verify(state, never()).cancel(any());
    }

    private void pending(boolean compensating) {
        when(state.read(1L)).thenReturn(new BookingSagaSnapshot(booking(BookingStatus.PENDING), "key", compensating));
    }
    private BookingResponse booking(BookingStatus status) {
        return new BookingResponse(1L, request.roomId(), request.startDate(), request.endDate(), status, Instant.EPOCH);
    }
    private ReservationResponse remote(ReservationStatus status) {
        return new ReservationResponse(1L, request.roomId(), request.startDate(), request.endDate(), status);
    }
}
