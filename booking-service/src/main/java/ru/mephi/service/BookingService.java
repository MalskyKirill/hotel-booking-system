package ru.mephi.service;

import org.springframework.stereotype.Service;
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

@Service
public class BookingService {

    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;

    public BookingService(BookingRepository bookingRepository,
                          UserRepository userRepository) {
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public BookingResponse create(CreateBookingRequest request, String username) {
        // Найти пользователя (JWT валиден, но пользователь мог быть удалён)
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new UserNotFoundException(username));

        // Идемпотентность: если requestId уже использован — сравнить payload
        Optional<Booking> existing = bookingRepository.findByRequestId(request.requestId());
        if (existing.isPresent()) {
            Booking b = existing.get();
            boolean sameOperation =
                b.getUser().getId().equals(user.getId())
                    && b.getRoomId().equals(request.roomId())
                    && b.getStartDate().equals(request.startDate())
                    && b.getEndDate().equals(request.endDate());

            if (!sameOperation) {
                throw new IdempotencyConflictException(request.requestId());
            }
            // Тот же запрос повторно — вернуть кэшированный результат
            return toResponse(b);
        }

        // Создать PENDING
        Booking booking = new Booking();
        booking.setUser(user);
        booking.setRoomId(request.roomId());
        booking.setStartDate(request.startDate());
        booking.setEndDate(request.endDate());
        booking.setRequestId(request.requestId());
        booking.setStatus(BookingStatus.PENDING);

        Booking saved = bookingRepository.save(booking);
        return toResponse(saved);
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
