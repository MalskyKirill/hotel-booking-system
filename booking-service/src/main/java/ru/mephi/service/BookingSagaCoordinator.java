package ru.mephi.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.mephi.client.HotelReservationClient;
import ru.mephi.dto.*;
import ru.mephi.exception.*;
import ru.mephi.model.BookingStatus;

@Service
public class BookingSagaCoordinator {
    private static final Logger log = LoggerFactory.getLogger(BookingSagaCoordinator.class);
    private final BookingSagaTransactions state;
    private final BookingCreationTransactions transactions;
    private final HotelReservationClient hotel;
    private final HotelCallRetry retry;

    public BookingSagaCoordinator(BookingSagaTransactions state, BookingCreationTransactions transactions,
                                  HotelReservationClient hotel, HotelCallRetry retry) {
        this.state = state;
        this.transactions = transactions;
        this.hotel = hotel;
        this.retry = retry;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public BookingResponse resume(Long id) {
        BookingSagaSnapshot snapshot = state.read(id);
        if (snapshot.booking().status() != BookingStatus.PENDING) return snapshot.booking();
        BookingResponse booking = snapshot.booking();
        CreateReservationRequest request = new CreateReservationRequest(id, booking.roomId(), booking.startDate(), booking.endDate());
        log.info("Resume Saga: bookingId={}, requestId={}, compensating={}", id, snapshot.requestId(), snapshot.compensating());
        try {
            if (snapshot.compensating()) return compensate(request);
            try {
                ReservationResponse reserved = retry.execute(id, "reserve", () -> hotel.reserve(request));
                if (reserved.status() == ReservationStatus.HELD) {
                    retry.execute(id, "confirm", () -> hotel.confirm(request));
                }
            } catch (RemoteServiceUncertainException | RemoteBusinessConflictException
                     | RemoteRoomNotFoundException | RemoteHotelRequestException ex) {
                state.beginCompensation(id);
                log.warn("Begin compensation: bookingId={}, requestId={}", id, snapshot.requestId());
                return compensate(request);
            }
            return transactions.confirm(id);
        } catch (RemoteServiceUncertainException | RemoteHotelRequestException
                 | RemoteBusinessConflictException | RemoteReservationNotFoundException | DataAccessException ex) {
            log.warn("Defer Saga: bookingId={}, requestId={}, cause={}", id, snapshot.requestId(), ex.getClass().getSimpleName());
            return state.defer(id);
        }
    }

    private BookingResponse compensate(CreateReservationRequest request) {
        Long id = request.bookingId();
        try {
            ReservationResponse remote = retry.execute(id, "getStatus", () -> hotel.getStatus(request));
            if (remote.status() == ReservationStatus.CONFIRMED) return transactions.confirm(id);
            if (remote.status() == ReservationStatus.RELEASED) return state.cancel(id);
        } catch (RemoteReservationNotFoundException ignored) {
            // Отсутствие записи не исключает запоздавший reserve: нужен RELEASED-маркер.
        }
        try {
            retry.execute(id, "release", () -> hotel.release(request));
            log.info("Compensation complete: bookingId={}", id);
            return state.cancel(id);
        } catch (RemoteRoomNotFoundException ignored) {
            // Несуществующий номер не может иметь резерв (FK в Hotel).
            return state.cancel(id);
        } catch (RemoteBusinessConflictException ex) {
            // confirm мог выиграть гонку с release. Уточняем результат, не отменяем вслепую.
            ReservationResponse remote = retry.execute(id, "getStatus", () -> hotel.getStatus(request));
            if (remote.status() == ReservationStatus.CONFIRMED) return transactions.confirm(id);
            if (remote.status() == ReservationStatus.RELEASED) return state.cancel(id);
            throw ex;
        }
    }
}
