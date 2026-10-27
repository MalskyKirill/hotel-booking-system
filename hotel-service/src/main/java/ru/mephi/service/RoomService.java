package ru.mephi.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.mephi.dto.CreateRoomRequest;
import ru.mephi.dto.RoomResponse;
import ru.mephi.exception.HotelNotFoundException;
import ru.mephi.exception.RoomAlreadyExistsException;
import ru.mephi.model.Hotel;
import ru.mephi.model.Room;
import ru.mephi.repository.HotelRepository;
import ru.mephi.repository.RoomRepository;

@Service
public class RoomService {
    private final RoomRepository roomRepository;
    private final HotelRepository hotelRepository;

    public RoomService(RoomRepository roomRepository, HotelRepository hotelRepository) {
        this.roomRepository = roomRepository;
        this.hotelRepository = hotelRepository;
    }

    @Transactional
    public RoomResponse create(CreateRoomRequest req) {
        Hotel hotel = hotelRepository.findById(req.getHotelId()).orElseThrow(() -> new HotelNotFoundException(req.getHotelId()));

        if(roomRepository.existsByHotelIdAndRoomNumber(req.getHotelId(), req.getRoomNumber())) {
            throw new RoomAlreadyExistsException(req.getHotelId(), req.getRoomNumber());
        }

        Room room = new Room();
        room.setHotel(hotel);
        room.setRoomNumber(req.getRoomNumber().trim());
        room.setCapacity(req.getCapacity());
        room.setPrice(req.getPrice());

        room.setAvailable(true);
        room.setTimesBooked(0);

        Room saved = roomRepository.save(room);

        return new RoomResponse(saved.getId(),
            saved.getHotel().getId(),
            saved.getRoomNumber(),
            saved.getCapacity(),
            saved.getPrice(),
            saved.getAvailable(),
            saved.getTimesBooked());

    }
}
