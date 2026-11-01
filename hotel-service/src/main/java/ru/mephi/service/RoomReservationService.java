package ru.mephi.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import ru.mephi.dto.CreateReservationRequest;
import ru.mephi.dto.ReservationResponse;
import ru.mephi.exception.IdempotencyConflictException;
import ru.mephi.exception.ReservationNotFoundException;

@Service
public class RoomReservationService {
    private final RoomReservationTransactions transactions;

    public RoomReservationService(RoomReservationTransactions transactions) {
        this.transactions = transactions;
    }

    public ReservationResponse reserve(CreateReservationRequest request) {
        try {
            return transactions.reserve(request);
        } catch (DataIntegrityViolationException ex) {
            return readWinner(request, ex);
        }
    }

    public ReservationResponse release(CreateReservationRequest request) {
        try {
            return transactions.release(request);
        } catch (DataIntegrityViolationException ex) {
            readWinner(request, ex);
            return transactions.release(request);
        }
    }

    public ReservationResponse cancel(CreateReservationRequest request) {
        try {
            return transactions.cancel(request);
        } catch (DataIntegrityViolationException ex) {
            readWinner(request, ex);
            return transactions.cancel(request);
        }
    }

    public ReservationResponse confirm(Long bookingId) {
        return transactions.confirm(bookingId);
    }

    public ReservationResponse release(Long bookingId) {
        return transactions.release(bookingId);
    }

    public ReservationResponse getByBookingId(Long bookingId) {
        return transactions.getByBookingId(bookingId);
    }

    private ReservationResponse readWinner(CreateReservationRequest request, DataIntegrityViolationException failure) {
        ReservationResponse existing;
        try {
            existing = transactions.getByBookingId(request.bookingId());
        } catch (ReservationNotFoundException ex) {
            throw failure;
        }
        if (!existing.roomId().equals(request.roomId())
            || !existing.startDate().equals(request.startDate())
            || !existing.endDate().equals(request.endDate())) {
            throw new IdempotencyConflictException(request.bookingId());
        }
        return existing;
    }
}
