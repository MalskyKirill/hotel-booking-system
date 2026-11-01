package ru.mephi.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.mephi.dto.BookingResponse;
import ru.mephi.dto.CreateBookingRequest;
import ru.mephi.exception.IdempotencyConflictException;
import ru.mephi.exception.UserNotFoundException;
import ru.mephi.model.Booking;
import ru.mephi.model.BookingStatus;
import ru.mephi.model.User;
import ru.mephi.repository.BookingRepository;
import ru.mephi.repository.UserRepository;

import java.util.Optional;

/** Локальные транзакции создания и подтверждения Booking, отделённые от HTTP-вызовов. */
@Service
public class BookingCreationTransactions {

    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;

    public BookingCreationTransactions(BookingRepository bookingRepository,
                                       UserRepository userRepository) {
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BookingResponse create(CreateBookingRequest request, String username) {
        // Найти пользователя (JWT валиден, но пользователь мог быть удалён)
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new UserNotFoundException(username));

        Optional<BookingResponse> existing = findExisting(request, user);
        if (existing.isPresent()) {
            return existing.get();
        }

        // Создать PENDING
        Booking booking = new Booking();
        booking.setUser(user);
        booking.setRoomId(request.roomId());
        booking.setAutoSelect(request.autoSelect());
        booking.setHotelId(request.hotelId());
        booking.setStartDate(request.startDate());
        booking.setEndDate(request.endDate());
        booking.setRequestId(request.requestId());
        booking.setStatus(BookingStatus.PENDING);

        Booking saved = bookingRepository.save(booking);
        return toResponse(saved);
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<BookingResponse> findExisting(CreateBookingRequest request, String username) {
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new UserNotFoundException(username));
        return findExisting(request, user);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BookingResponse confirm(Long bookingId) {
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
            .orElseThrow(() -> new IllegalStateException("Booking not found: " + bookingId));
        if (booking.getStatus() == BookingStatus.CANCELLED) {
            throw new IllegalStateException("Cannot confirm cancelled booking: " + bookingId);
        }
        if (booking.isAutoSelect() && booking.getRoomId() == null) {
            throw new IllegalStateException("Automatic selection has no room: " + bookingId);
        }
        booking.setStatus(BookingStatus.CONFIRMED);
        return toResponse(booking);
    }

    private Optional<BookingResponse> findExisting(CreateBookingRequest request, User user) {
        return bookingRepository.findByRequestId(request.requestId()).map(booking -> {
            boolean sameOperation = booking.getUser().getId().equals(user.getId())
                && booking.isAutoSelect() == request.autoSelect()
                && java.util.Objects.equals(booking.getHotelId(), request.hotelId())
                && (request.autoSelect() || java.util.Objects.equals(booking.getRoomId(), request.roomId()))
                && booking.getStartDate().equals(request.startDate())
                && booking.getEndDate().equals(request.endDate());
            if (!sameOperation) {
                throw new IdempotencyConflictException(request.requestId());
            }
            return toResponse(booking);
        });
    }

    private BookingResponse toResponse(Booking b) {
        return new BookingResponse(
            b.getId(),
            b.getRoomId(),
            b.getStartDate(),
            b.getEndDate(),
            b.getStatus(),
            b.getCreatedAt()
        );
    }
}
