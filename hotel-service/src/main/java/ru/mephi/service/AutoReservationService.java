package ru.mephi.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import ru.mephi.dto.*;
import ru.mephi.exception.*;
import ru.mephi.repository.RoomRepository;
import ru.mephi.model.ReservationStatus;
import java.util.Set;

@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class AutoReservationService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AutoReservationService.class);
    private final AutoReservationTransactions transactions;
    private final RoomRepository rooms;
    private final int candidateLimit;
    public AutoReservationService(AutoReservationTransactions transactions, RoomRepository rooms,
        @Value("${hotel.auto-selection.candidate-limit:20}") int candidateLimit) {
        if (candidateLimit < 1 || candidateLimit > 100) throw new IllegalArgumentException("Invalid candidate limit");
        this.transactions = transactions; this.rooms = rooms; this.candidateLimit = candidateLimit;
    }
    private void ensure(AutoReservationRequest request) {
        try { transactions.ensure(request); }
        catch (DataIntegrityViolationException ex) { transactions.ensure(request); }
    }
    public ReservationResponse reserve(AutoReservationRequest request) {
        ensure(request);
        var previous = transactions.inspect(request);
        if (previous != null) return previous;
        var candidates = rooms.findFree(request.hotelId(), request.startDate(), request.endDate(),
            Set.of(ReservationStatus.HELD, ReservationStatus.CONFIRMED),
            PageRequest.of(0, candidateLimit, Sort.by("timesBooked", "id")));
        for (var candidate : candidates) {
            var result = transactions.attempt(request, candidate.getId());
            if (result != null) {
                log.info("Auto selection: bookingId={}, roomId={}, status={}", request.bookingId(), result.roomId(), result.status());
                return result;
            }
            log.info("Auto candidate occupied: bookingId={}, roomId={}", request.bookingId(), candidate.getId());
        }
        previous = transactions.inspect(request);
        if (previous != null) return previous;
        throw new NoAvailableRoomException();
    }
    public ReservationResponse confirm(AutoReservationRequest request) {
        ensure(request); return transactions.confirm(request);
    }
    public ReservationResponse release(AutoReservationRequest request) {
        ensure(request); return transactions.release(request);
    }
    public ReservationResponse getStatus(AutoReservationRequest request) {
        ensure(request);
        var result = transactions.inspect(request);
        if (result == null) throw new ReservationNotFoundException(request.bookingId());
        return result;
    }
}
