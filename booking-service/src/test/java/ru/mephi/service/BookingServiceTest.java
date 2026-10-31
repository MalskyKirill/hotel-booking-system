package ru.mephi.service;

import org.junit.jupiter.api.Test;
import ru.mephi.client.HotelReservationClient;
import ru.mephi.dto.*;
import ru.mephi.exception.RemoteServiceUncertainException;
import ru.mephi.model.BookingStatus;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookingServiceTest {
    private final BookingPendingService pending = mock(BookingPendingService.class);
    private final BookingCreationTransactions transactions = mock(BookingCreationTransactions.class);
    private final HotelReservationClient hotel = mock(HotelReservationClient.class);
    private final BookingService service = new BookingService(pending, transactions, hotel);
    private final CreateBookingRequest request = new CreateBookingRequest(2L,
        LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 3), "request-1");
    private final CreateReservationRequest reservation = new CreateReservationRequest(1L, request.roomId(),
        request.startDate(), request.endDate());
    private final Instant createdAt = Instant.parse("2026-10-31T00:00:00Z");

    @Test
    void commitsPendingThenReservesThenConfirmsBothServices() {
        when(pending.create(request, "alice")).thenReturn(booking(BookingStatus.PENDING));
        when(hotel.reserve(reservation)).thenReturn(remote(ReservationStatus.HELD));
        when(hotel.confirm(reservation)).thenReturn(remote(ReservationStatus.CONFIRMED));
        when(transactions.confirm(1L)).thenReturn(booking(BookingStatus.CONFIRMED));

        assertEquals(booking(BookingStatus.CONFIRMED), service.create(request, "alice"));

        var order = inOrder(pending, hotel, transactions);
        order.verify(pending).create(request, "alice");
        order.verify(hotel).reserve(reservation);
        order.verify(hotel).confirm(reservation);
        order.verify(transactions).confirm(1L);
        verifyNoMoreInteractions(hotel);
    }

    @Test
    void resumesPendingWhenHotelAlreadyConfirmed() {
        when(pending.create(request, "alice")).thenReturn(booking(BookingStatus.PENDING));
        when(hotel.reserve(reservation)).thenReturn(remote(ReservationStatus.CONFIRMED));
        when(transactions.confirm(1L)).thenReturn(booking(BookingStatus.CONFIRMED));

        assertEquals(BookingStatus.CONFIRMED, service.create(request, "alice").status());
        verify(hotel, never()).confirm(any());
        verify(transactions).confirm(1L);
    }

    @Test
    void confirmedRepeatDoesNotCallHotel() {
        when(pending.create(request, "alice")).thenReturn(booking(BookingStatus.CONFIRMED));

        assertEquals(booking(BookingStatus.CONFIRMED), service.create(request, "alice"));
        verifyNoInteractions(hotel, transactions);
    }

    @Test
    void cancelledRepeatDoesNotRestartSaga() {
        when(pending.create(request, "alice")).thenReturn(booking(BookingStatus.CANCELLED));

        assertEquals(BookingStatus.CANCELLED, service.create(request, "alice").status());
        verifyNoInteractions(hotel, transactions);
    }

    @Test
    void uncertainConfirmationDoesNotMarkBookingConfirmed() {
        when(pending.create(request, "alice")).thenReturn(booking(BookingStatus.PENDING));
        when(hotel.reserve(reservation)).thenReturn(remote(ReservationStatus.HELD));
        when(hotel.confirm(reservation)).thenThrow(new RemoteServiceUncertainException("Lost response"));

        assertThrows(RemoteServiceUncertainException.class, () -> service.create(request, "alice"));
        verifyNoInteractions(transactions);
        verify(hotel, never()).release(any());
    }

    private BookingResponse booking(BookingStatus status) {
        return new BookingResponse(1L, request.roomId(), request.startDate(), request.endDate(), status, createdAt);
    }

    private ReservationResponse remote(ReservationStatus status) {
        return new ReservationResponse(1L, request.roomId(), request.startDate(), request.endDate(), status);
    }
}
