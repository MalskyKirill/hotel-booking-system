package ru.mephi.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.mephi.dto.CreateReservationRequest;
import ru.mephi.dto.ReservationResponse;
import ru.mephi.exception.*;
import ru.mephi.model.ReservationStatus;
import ru.mephi.model.Room;
import ru.mephi.model.RoomReservation;
import ru.mephi.repository.RoomRepository;
import ru.mephi.repository.RoomReservationRepository;

import java.util.Optional;
import java.util.Set;

@Service
public class RoomReservationService {

    private static final Set<ReservationStatus> BLOCKING_STATUSES =
        Set.of(ReservationStatus.HELD, ReservationStatus.CONFIRMED);

    private final RoomRepository roomRepository;
    private final RoomReservationRepository reservationRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public RoomReservationService(RoomRepository roomRepository,
                                  RoomReservationRepository reservationRepository) {
        this.roomRepository = roomRepository;
        this.reservationRepository = reservationRepository;
    }

    @Transactional
    public ReservationResponse reserve(CreateReservationRequest request) {
        // Блокируем Room (PESSIMISTIC_WRITE)
        Room room = roomRepository.findByIdForUpdate(request.roomId())
            .orElseThrow(() -> new RoomNotFoundException(request.roomId()));

        // Проверка идемпотентности (ПОСЛЕ блокировки!)
        Optional<RoomReservation> existingOpt = reservationRepository.findByBookingId(request.bookingId());

        if (existingOpt.isPresent()) {
            RoomReservation existing = existingOpt.get();

            // Проверяем, что это тот же самый запрос
            boolean isSameOperation = existing.getRoom().getId().equals(request.roomId())
                && existing.getStartDate().equals(request.startDate())
                && existing.getEndDate().equals(request.endDate());

            if (!isSameOperation) {
                // Тот же bookingId, но другие параметры — это ошибка клиента
                throw new IdempotencyConflictException(request.bookingId());
            }

            // Тот же запрос -> возвращаем текущий статус (HELD, CONFIRMED или RELEASED)
            return toResponse(existing);
        }

        //  Проверка доступности номера
        if (!room.getAvailable()) {
            throw new RoomUnavailableException(request.roomId());
        }

        // Проверка пересечения дат
        boolean hasConflict = reservationRepository.existsConflictReservation(
            request.roomId(),
            request.startDate(),
            request.endDate(),
            BLOCKING_STATUSES
        );

        // Если конфликт — отклоняем
        if (hasConflict) {
            throw new ReservationConflictException(request.roomId(),
                "Dates " + request.startDate() + " to " + request.endDate() + " are already booked");
        }

        // Создаем HELD
        RoomReservation reservation = new RoomReservation();
        reservation.setRoom(room);
        reservation.setBookingId(request.bookingId());
        reservation.setStartDate(request.startDate());
        reservation.setEndDate(request.endDate());
        reservation.setStatus(ReservationStatus.HELD);

        // timesBooked пока не увеличиваем

        // Сохраняем
        RoomReservation saved = reservationRepository.save(reservation);

        return toResponse(saved);
    }

    @Transactional
    public ReservationResponse confirm(Long bookingId) {
        //  Первичное чтение (чтобы узнать roomId)
        RoomReservation reservation = reservationRepository.findByBookingId(bookingId)
            .orElseThrow(() -> new ReservationNotFoundException(bookingId));

        // Блокировка Room
        Room room = roomRepository.findByIdForUpdate(reservation.getRoom().getId())
            .orElseThrow(() -> new RoomNotFoundException(reservation.getRoom().getId()));

        //  Перечитываем актуальное состояние резервирования (Refresh)
        entityManager.refresh(reservation);

        // Проверка статуса и обработка
        switch (reservation.getStatus()) {
            case HELD:
                // Меняем статус
                reservation.setStatus(ReservationStatus.CONFIRMED);
                // Увеличиваем счетчик ровно один раз
                room.setTimesBooked(room.getTimesBooked() + 1);
                // Сохраняем (можно полагаться на dirty checking, но явный save() нагляднее)
                reservationRepository.save(reservation);
                roomRepository.save(room);
                break;

            case CONFIRMED:
                // Идемпотентный повтор - ничего не меняем
                break;

            case RELEASED:
                throw new InvalidReservationStateException(bookingId,
                    "Cannot confirm a reservation that was released");

            default:
                throw new IllegalStateException("Unknown status: " + reservation.getStatus());
        }

        return toResponse(reservation);
    }

    @Transactional
    public ReservationResponse release(Long bookingId) {
        // Первичное чтение
        RoomReservation reservation = reservationRepository.findByBookingId(bookingId)
            .orElseThrow(() -> new ReservationNotFoundException(bookingId));

        //  Блокировка Room
        Room room = roomRepository.findByIdForUpdate(reservation.getRoom().getId())
            .orElseThrow(() -> new RoomNotFoundException(reservation.getRoom().getId()));

        //  Перечитываем актуальное состояние
        entityManager.refresh(reservation);

        // Проверка статуса и обработка
        switch (reservation.getStatus()) {
            case HELD:
                // Освобождаем
                reservation.setStatus(ReservationStatus.RELEASED);
                reservationRepository.save(reservation);
                break;

            case RELEASED:
                // Идемпотентный повтор - ничего не делаем
                break;

            case CONFIRMED:
                // Запрещено освобождать подтвержденные брони через этот метод
                throw new InvalidReservationStateException(bookingId,
                    "Cannot release a confirmed reservation. Use cancellation instead.");

            default:
                throw new IllegalStateException("Unknown status: " + reservation.getStatus());
        }

        return toResponse(reservation);
    }

    @Transactional(readOnly = true)
    public ReservationResponse getByBookingId(Long bookingId) {
        RoomReservation reservation = reservationRepository.findByBookingId(bookingId)
            .orElseThrow(() -> new ReservationNotFoundException(bookingId));

        return toResponse(reservation);
    }

    private ReservationResponse toResponse(RoomReservation r) {
        return new ReservationResponse(
            r.getBookingId(),
            r.getRoom().getId(),
            r.getStartDate(),
            r.getEndDate(),
            r.getStatus()
        );
    }
}
