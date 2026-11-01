package ru.mephi.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import ru.mephi.dto.*;
import ru.mephi.model.*;
import ru.mephi.repository.*;
import ru.mephi.exception.*;
import java.util.Set;

@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class AutoReservationTransactions {
    private final AutoReservationOperationRepository operations;
    private final RoomReservationRepository reservations;
    private final RoomRepository rooms;
    private final RoomReservationTransactions normal;

    public AutoReservationTransactions(AutoReservationOperationRepository operations,
        RoomReservationRepository reservations, RoomRepository rooms, RoomReservationTransactions normal) {
        this.operations = operations; this.reservations = reservations; this.rooms = rooms; this.normal = normal;
    }
    public void ensure(AutoReservationRequest request) {
        var existing = operations.findById(request.bookingId());
        if (existing.isPresent()) existing.get().validate(request);
        else operations.saveAndFlush(new AutoReservationOperation(request));
    }
    private AutoReservationOperation lock(AutoReservationRequest request) {
        var operation = operations.lock(request.bookingId()).orElseThrow();
        operation.validate(request);
        return operation;
    }
    private ReservationResponse existing(AutoReservationRequest request, AutoReservationOperation operation) {
        var existing = reservations.findByBookingId(request.bookingId());
        if (existing.isPresent()) {
            var r = existing.get();
            if (!r.getStartDate().equals(request.startDate()) || !r.getEndDate().equals(request.endDate())
                || (request.hotelId() != null && !r.getRoom().getHotel().getId().equals(request.hotelId()))) {
                throw new IdempotencyConflictException(request.bookingId());
            }
            return new ReservationResponse(r.getBookingId(), r.getRoom().getId(), r.getStartDate(), r.getEndDate(), r.getStatus());
        }
        return operation.isCancelled() ? new ReservationResponse(request.bookingId(), null,
            request.startDate(), request.endDate(), ReservationStatus.RELEASED) : null;
    }
    public ReservationResponse inspect(AutoReservationRequest request) {
        return existing(request, lock(request));
    }
    public ReservationResponse attempt(AutoReservationRequest request, Long roomId) {
        var operation = lock(request);
        var previous = existing(request, operation);
        if (previous != null) return previous;
        var room = rooms.findByIdForUpdate(roomId).orElse(null);
        if (room == null || !room.getAvailable() ||
            (request.hotelId() != null && !room.getHotel().getId().equals(request.hotelId())) ||
            reservations.existsConflictReservation(roomId, request.startDate(), request.endDate(),
                Set.of(ReservationStatus.HELD, ReservationStatus.CONFIRMED))) return null;
        var reservation = new RoomReservation();
        reservation.setBookingId(request.bookingId()); reservation.setRoom(room);
        reservation.setStartDate(request.startDate()); reservation.setEndDate(request.endDate());
        reservation.setStatus(ReservationStatus.HELD);
        reservations.save(reservation);
        return new ReservationResponse(request.bookingId(), roomId, request.startDate(), request.endDate(), ReservationStatus.HELD);
    }
    public ReservationResponse confirm(AutoReservationRequest request) {
        var operation = lock(request);
        if (operation.isCancelled()) throw new InvalidReservationStateException(request.bookingId(), "Operation cancelled");
        existing(request, operation);
        return normal.confirmInTransaction(request.bookingId());
    }
    public ReservationResponse cancel(AutoReservationRequest request) {
        var operation = lock(request);
        var previous = existing(request, operation);
        if (previous != null && previous.roomId() != null) normal.cancelInTransaction(request.bookingId());
        operation.cancel();
        return existing(request, operation);
    }

    public ReservationResponse release(AutoReservationRequest request) {
        var operation = lock(request);
        var previous = existing(request, operation);
        if (previous != null && previous.roomId() != null) normal.releaseInTransaction(request.bookingId());
        operation.cancel();
        return existing(request, operation);
    }
}
