package ru.mephi.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.mephi.model.Room;

import java.util.List;

public interface RoomRepository extends JpaRepository<Room, Long> {

    List<Room> findByHotel_Id(Long hotelId);

    boolean existsByHotelId(Long hotelId);
    boolean existsByHotelIdAndRoomNumber(Long hotelId, String roomNumber);
}
