package ru.mephi.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.mephi.client.HotelReservationClient;
import ru.mephi.dto.BookingResponse;
import ru.mephi.dto.CreateBookingRequest;
import ru.mephi.dto.CreateReservationRequest;
import ru.mephi.dto.ReservationResponse;
import ru.mephi.dto.ReservationStatus;
import ru.mephi.model.BookingStatus;

@Service
public class BookingService {
    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final BookingPendingService pendingService;
    private final BookingCreationTransactions transactions;
    private final HotelReservationClient hotelClient;

    public BookingService(BookingPendingService pendingService,
                          BookingCreationTransactions transactions,
                          HotelReservationClient hotelClient) {
        this.pendingService = pendingService;
        this.transactions = transactions;
        this.hotelClient = hotelClient;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public BookingResponse create(CreateBookingRequest request, String username) {
        BookingResponse booking = pendingService.create(request, username);
        if (booking.status() != BookingStatus.PENDING) {
            return booking;
        }
        log.info("Booking pending: bookingId={}, requestId={}", booking.id(), request.requestId());
        CreateReservationRequest reservation = new CreateReservationRequest(booking.id(), booking.roomId(),
            booking.startDate(), booking.endDate());
        ReservationResponse reserved = hotelClient.reserve(reservation);
        if (reserved.status() == ReservationStatus.HELD) {
            log.info("Room held: bookingId={}, requestId={}", booking.id(), request.requestId());
            hotelClient.confirm(reservation);
        }
        BookingResponse confirmed = transactions.confirm(booking.id());
        log.info("Booking confirmed: bookingId={}, requestId={}", confirmed.id(), request.requestId());
        return confirmed;
    }
}
