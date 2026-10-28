package ru.mephi.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.mephi.model.Booking;
import ru.mephi.model.BookingStatus;

import java.time.LocalDate;

public interface BookingRepository extends JpaRepository<Booking, Long> {
    boolean existsByRequestId(String requestId);

    @Query("SELECT COUNT(b) > 0 FROM Booking b " +
        "WHERE b.roomId = :roomId " +
        "AND b.status <> :excludedStatus " +
        "AND b.startDate < :endDate " +
        "AND b.endDate > :startDate")
    boolean existsConflictBooking(
        @Param("roomId") Long roomId,
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate,
        @Param("excludedStatus") BookingStatus excludedStatus
    );
}
