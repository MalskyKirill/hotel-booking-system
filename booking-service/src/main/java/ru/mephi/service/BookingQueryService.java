package ru.mephi.service;

import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.mephi.dto.*;
import ru.mephi.exception.BookingNotFoundException;
import ru.mephi.model.Booking;
import ru.mephi.repository.BookingRepository;

@Service
@Transactional(readOnly = true)
public class BookingQueryService {
    private final BookingRepository bookings;
    public BookingQueryService(BookingRepository bookings) { this.bookings = bookings; }

    public BookingResponse get(Long id, String username) {
        return map(bookings.findByIdAndUser_Username(id, username).orElseThrow(BookingNotFoundException::new));
    }
    public BookingPage history(String username, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page >= 0 and 1 <= size <= 100 required");
        }
        var result = bookings.findByUser_Username(username,
            PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
        return new BookingPage(result.map(this::map).getContent(), page, size, result.getTotalElements(), result.getTotalPages());
    }
    private BookingResponse map(Booking booking) {
        return BookingResponse.from(booking);
    }
}
