package ru.mephi.model;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.Objects;
import ru.mephi.dto.AutoReservationRequest;
import ru.mephi.exception.IdempotencyConflictException;

@Entity
public class AutoReservationOperation {
    @Id private Long bookingId;
    @Version private Long version;
    private Long hotelId;
    @Column(nullable = false) private LocalDate startDate;
    @Column(nullable = false) private LocalDate endDate;
    @Column(nullable = false) private boolean cancelled;

    protected AutoReservationOperation() { }
    public AutoReservationOperation(AutoReservationRequest request) {
        bookingId = request.bookingId(); hotelId = request.hotelId();
        startDate = request.startDate(); endDate = request.endDate();
    }
    public void validate(AutoReservationRequest request) {
        if (!Objects.equals(hotelId, request.hotelId()) || !startDate.equals(request.startDate())
            || !endDate.equals(request.endDate())) throw new IdempotencyConflictException(bookingId);
    }
    public boolean isCancelled() { return cancelled; }
    public void cancel() { cancelled = true; }
}
