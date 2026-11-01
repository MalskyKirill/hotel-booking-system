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
        if (snapshot.booking().status() != BookingStatus.PENDING && !snapshot.booking().cancellationRequested()) return snapshot.booking();
        BookingResponse booking = snapshot.booking();
        CreateReservationRequest request = new CreateReservationRequest(id, booking.roomId(), booking.startDate(), booking.endDate());
        log.info("Resume Saga: bookingId={}, requestId={}, compensating={}", id, snapshot.requestId(), snapshot.compensating());
        AutoReservationRequest auto = snapshot.autoSelect()
            ? new AutoReservationRequest(id, snapshot.hotelId(), booking.startDate(), booking.endDate()) : null;
        try {
            if (booking.cancellationRequested()) return cancelRequested(request, auto);
            if (snapshot.compensating()) return compensate(request, auto);
            try {
                ReservationResponse reserved = retry.execute(id, "reserve", () -> auto == null ? hotel.reserve(request) : hotel.reserveAuto(auto));
                rememberSelection(auto, reserved);
                if (reserved.status() == ReservationStatus.HELD) {
                    retry.execute(id, "confirm", () -> auto == null ? hotel.confirm(request) : hotel.confirmAuto(auto));
                }
            } catch (RemoteServiceUncertainException | RemoteBusinessConflictException
                     | RemoteRoomNotFoundException | RemoteHotelRequestException ex) {
                state.beginCompensation(id);
                log.warn("Begin compensation: bookingId={}, requestId={}", id, snapshot.requestId());
                return compensate(request, auto);
            }
            return confirmed(id);
        } catch (RemoteServiceUncertainException | RemoteHotelRequestException
                 | RemoteBusinessConflictException | RemoteReservationNotFoundException | DataAccessException ex) {
            log.warn("Defer Saga: bookingId={}, requestId={}, cause={}", id, snapshot.requestId(), ex.getClass().getSimpleName());
            return state.defer(id);
        }
    }

    private BookingResponse cancelRequested(CreateReservationRequest request, AutoReservationRequest auto) {
        Long id = request.bookingId();
        try {
            ReservationResponse released = retry.execute(id, "cancel",
                () -> auto == null ? hotel.cancel(request) : hotel.cancelAuto(auto));
            rememberSelection(auto, released);
            return cancelled(id);
        } catch (RemoteRoomNotFoundException ignored) {
            return cancelled(id);
        } catch (RemoteServiceUncertainException ex) {
            // Потерянный ответ отмены: терминальным считаем только наблюдаемый RELEASED.
            ReservationResponse remote = retry.execute(id, "getStatus",
                () -> auto == null ? hotel.getStatus(request) : hotel.getAutoStatus(auto));
            rememberSelection(auto, remote);
            if (remote.status() == ReservationStatus.RELEASED) return cancelled(id);
            throw ex;
        }
    }

    private void rememberSelection(AutoReservationRequest request, ReservationResponse response) {
        if (request != null && response.roomId() != null) state.selectRoom(request.bookingId(), response.roomId());
    }

    private BookingResponse confirmed(Long id) {
        BookingResponse result = transactions.confirm(id);
        log.info("Saga settled: bookingId={}, status={}", id, result.status());
        return result;
    }

    private BookingResponse cancelled(Long id) {
        BookingResponse result = state.cancel(id);
        log.info("Saga settled: bookingId={}, status={}", id, result.status());
        return result;
    }

    private BookingResponse compensate(CreateReservationRequest request, AutoReservationRequest auto) {
        Long id = request.bookingId();
        try {
            ReservationResponse remote = retry.execute(id, "getStatus", () -> auto == null ? hotel.getStatus(request) : hotel.getAutoStatus(auto));
            rememberSelection(auto, remote);
            if (remote.status() == ReservationStatus.CONFIRMED) return confirmed(id);
            if (remote.status() == ReservationStatus.RELEASED) return cancelled(id);
        } catch (RemoteReservationNotFoundException ignored) {
            // Отсутствие записи не исключает запоздавший reserve: нужен RELEASED-маркер.
        }
        try {
            ReservationResponse released = retry.execute(id, "release",
                () -> auto == null ? hotel.release(request) : hotel.releaseAuto(auto));
            rememberSelection(auto, released);
            return cancelled(id);
        } catch (RemoteRoomNotFoundException ignored) {
            // Несуществующий номер не может иметь резерв (FK в Hotel).
            return cancelled(id);
        } catch (RemoteBusinessConflictException ex) {
            // confirm мог выиграть гонку с release. Уточняем результат, не отменяем вслепую.
            ReservationResponse remote = retry.execute(id, "getStatus", () -> auto == null ? hotel.getStatus(request) : hotel.getAutoStatus(auto));
            rememberSelection(auto, remote);
            if (remote.status() == ReservationStatus.CONFIRMED) return confirmed(id);
            if (remote.status() == ReservationStatus.RELEASED) return cancelled(id);
            throw ex;
        }
    }
}
