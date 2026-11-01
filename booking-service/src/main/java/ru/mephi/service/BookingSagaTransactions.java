package ru.mephi.service;

import ru.mephi.exception.BookingNotFoundException;
import ru.mephi.exception.RemoteServiceUncertainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.mephi.dto.BookingResponse;
import ru.mephi.dto.BookingSagaSnapshot;
import ru.mephi.model.Booking;
import ru.mephi.model.BookingStatus;
import ru.mephi.repository.BookingRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;

@Service
public class BookingSagaTransactions {
    private final BookingRepository bookings;

    public BookingSagaTransactions(BookingRepository bookings) {
        this.bookings = bookings;
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public BookingSagaSnapshot read(Long id) {
        return snapshot(bookings.findById(id).orElseThrow());
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<Long> due(Instant now, int limit) {
        return bookings.findSagaDueIds(BookingStatus.PENDING, now, PageRequest.of(0, limit));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BookingResponse requestCancellation(Long id, String username) {
        Booking booking = bookings.findByIdForUpdate(id)
            .orElseThrow(BookingNotFoundException::new);
        if (!booking.getUser().getUsername().equals(username)) throw new BookingNotFoundException();
        if (booking.getStatus() != BookingStatus.CANCELLED) {
            booking.setCancellationRequested(true);
            booking.setSagaNextAttemptAt(Instant.now());
        }
        return snapshot(booking).booking();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void selectRoom(Long id, Long roomId) {
        Booking booking = bookings.findByIdForUpdate(id).orElseThrow();
        if (!booking.isAutoSelect()) throw new IllegalStateException("Not an automatic selection");
        if (booking.getRoomId() != null && !booking.getRoomId().equals(roomId)) {
            throw new RemoteServiceUncertainException("Selected room changed remotely");
        }
        if (booking.getStatus() == BookingStatus.PENDING || booking.isCancellationRequested()) booking.setRoomId(roomId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void beginCompensation(Long id) {
        Booking booking = bookings.findByIdForUpdate(id).orElseThrow();
        if (booking.getStatus() == BookingStatus.PENDING) booking.setSagaCompensating(true);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BookingResponse cancel(Long id) {
        Booking booking = bookings.findByIdForUpdate(id).orElseThrow();
        if (booking.getStatus() == BookingStatus.PENDING || booking.isCancellationRequested()) {
            booking.setStatus(BookingStatus.CANCELLED);
            booking.setCancellationRequested(false);
        }
        return snapshot(booking).booking();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BookingResponse defer(Long id) {
        Booking booking = bookings.findByIdForUpdate(id).orElseThrow();
        if (booking.getStatus() == BookingStatus.PENDING || booking.isCancellationRequested()) {
            int attempts = Math.min(30, booking.getSagaRecoveryAttempts() + 1);
            booking.setSagaRecoveryAttempts(attempts);
            booking.setSagaNextAttemptAt(Instant.now().plusSeconds(Math.min(300, 5L << Math.min(6, attempts - 1))));
        }
        return snapshot(booking).booking();
    }

    private BookingSagaSnapshot snapshot(Booking booking) {
        return new BookingSagaSnapshot(BookingResponse.from(booking),
            booking.getRequestId(), booking.isSagaCompensating(), booking.isAutoSelect(), booking.getHotelId());
    }
}
