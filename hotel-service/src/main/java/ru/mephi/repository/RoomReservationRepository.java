package ru.mephi.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.mephi.model.ReservationStatus;
import ru.mephi.model.RoomReservation;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;

public interface RoomReservationRepository extends JpaRepository<RoomReservation, Long> {
    boolean existsByRoom_Id(Long roomId);

    @Query("SELECT r FROM RoomReservation r WHERE r.room.id IN :ids AND r.status IN :statuses " +
        "AND r.startDate < :endDate AND r.endDate > :startDate")
    List<RoomReservation> findForStatistics(@Param("ids") List<Long> ids,
        @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate,
        @Param("statuses") Collection<ReservationStatus> statuses);

    Optional<RoomReservation> findByBookingId(Long bookingId);


    @Query("SELECT COUNT(r) > 0 FROM RoomReservation r " +
        "WHERE r.room.id = :roomId " +
        "AND r.status IN :blockingStatuses " +
        "AND r.startDate < :endDate " +
        "AND r.endDate > :startDate")
    boolean existsConflictReservation(
        @Param("roomId") Long roomId,
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate,
        @Param("blockingStatuses") Collection<ReservationStatus> blockingStatuses
    );
}
