package ru.mephi.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.mephi.model.Room;

import java.util.List;
import java.util.Optional;

public interface RoomRepository extends JpaRepository<Room, Long> {
    List<Room> findByHotel_Id(Long hotelId);
    boolean existsByHotelId(Long hotelId);
    boolean existsByHotel_IdAndRoomNumber(Long hotelId, String roomNumber);
    List<Room> findByAvailableTrue();
    List<Room> findByHotel_IdAndAvailableTrue(Long hotelId);
    List<Room> findByAvailableTrueOrderByTimesBookedAscIdAsc();
    List<Room> findByHotel_IdAndAvailableTrueOrderByTimesBookedAscIdAsc(Long hotelId);

    @Query("SELECT r FROM Room r WHERE r.available = true " +
        "AND (:hotelId IS NULL OR r.hotel.id = :hotelId) " +
        "AND NOT EXISTS (SELECT x.id FROM RoomReservation x WHERE x.room = r " +
        "AND x.status IN :statuses AND x.startDate < :endDate AND x.endDate > :startDate)")
    List<Room> findFree(@Param("hotelId") Long hotelId, @Param("startDate") java.time.LocalDate startDate,
                        @Param("endDate") java.time.LocalDate endDate,
                        @Param("statuses") java.util.Collection<ru.mephi.model.ReservationStatus> statuses,
                        org.springframework.data.domain.Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Room r WHERE r.id = :id")
    Optional<Room> findByIdForUpdate(@Param("id") Long id);

}
