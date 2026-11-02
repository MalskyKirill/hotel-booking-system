package ru.mephi.dto;

import java.time.Instant;
import java.time.LocalDate;
import ru.mephi.model.BookingStatus;

public record BookingResponse(
    Long id,
    Long roomId,
    LocalDate startDate,
    LocalDate endDate,
    BookingStatus status,
    Instant createdAt,
    boolean cancellationRequested) {
  public BookingResponse(
      Long id,
      Long roomId,
      LocalDate startDate,
      LocalDate endDate,
      BookingStatus status,
      Instant createdAt) {
    this(id, roomId, startDate, endDate, status, createdAt, false);
  }

  public static BookingResponse from(ru.mephi.model.Booking booking) {
    return new BookingResponse(
        booking.getId(),
        booking.getRoomId(),
        booking.getStartDate(),
        booking.getEndDate(),
        booking.getStatus(),
        booking.getCreatedAt(),
        booking.isCancellationRequested());
  }
}
