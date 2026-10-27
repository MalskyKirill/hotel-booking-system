package ru.mephi.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.mephi.model.Room;

import java.util.List;

public interface RoomRepository extends JpaRepository<Room, Long> {

    List<Room> findByHotel_Id(Long hotelId);

    boolean existsByHotelId(Long hotelId);
    boolean existsByHotel_IdAndRoomNumber(Long hotelId, String roomNumber);

    List<Room> findByAvailableTrue();

    List<Room> findByHotel_IdAndAvailableTrue(Long hotelId);

    List<Room> findByAvailableTrueOrderByTimesBookedAscIdAsc();
    List<Room> findByHotel_IdAndAvailableTrueOrderByTimesBookedAscIdAsc(Long hotelId);

}
