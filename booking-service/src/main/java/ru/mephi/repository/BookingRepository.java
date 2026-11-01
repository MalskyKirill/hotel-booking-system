package ru.mephi.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.mephi.model.Booking;
import ru.mephi.model.BookingStatus;

import java.time.LocalDate;
import java.util.Optional;
import java.util.List;
import java.time.Instant;
import org.springframework.data.domain.Pageable;

public interface BookingRepository extends JpaRepository<Booking, Long> {
    boolean existsByUser_Id(Long userId);
    Optional<Booking> findByIdAndUser_Username(Long id, String username);
    Page<Booking> findByUser_Username(String username, Pageable pageable);
    Optional<Booking> findByRequestId(String requestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Booking b WHERE b.id = :id")
    Optional<Booking> findByIdForUpdate(@Param("id") Long id);

    @Query("SELECT b.id FROM Booking b WHERE (b.status = :status OR b.cancellationRequested = true) AND b.sagaNextAttemptAt <= :now ORDER BY b.id")
    List<Long> findSagaDueIds(@Param("status") BookingStatus status, @Param("now") Instant now, Pageable pageable);

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
