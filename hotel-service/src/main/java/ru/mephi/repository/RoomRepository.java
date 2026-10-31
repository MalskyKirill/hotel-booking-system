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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Room r WHERE r.id = :id")
    Optional<Room> findByIdForUpdate(@Param("id") Long id);

}
