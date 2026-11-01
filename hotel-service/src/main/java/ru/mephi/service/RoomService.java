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

import java.util.List;
import java.util.stream.Collectors;

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
        String normalizedRoomNumber = req.getRoomNumber().trim();

        Hotel hotel = hotelRepository.findById(req.getHotelId()).orElseThrow(() -> new HotelNotFoundException(req.getHotelId()));

        if(roomRepository.existsByHotel_IdAndRoomNumber(req.getHotelId(), normalizedRoomNumber)) {
            throw new RoomAlreadyExistsException(req.getHotelId(), normalizedRoomNumber);
        }

        Room room = new Room();
        room.setHotel(hotel);
        room.setRoomNumber(normalizedRoomNumber);
        room.setCapacity(req.getCapacity());
        room.setPrice(req.getPrice());

        room.setAvailable(true);
        room.setTimesBooked(0);

        Room saved = roomRepository.save(room);

        return mapToResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<RoomResponse> getAvailableRooms(Long hotelId) {
        List<Room> rooms;

        if (hotelId != null) {
            rooms = roomRepository.findByHotel_IdAndAvailableTrue(hotelId);
        } else {
            rooms = roomRepository.findByAvailableTrue();
        }

        return rooms.stream().map(this::mapToResponse).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<RoomResponse> getRecommendedRooms(Long hotelId) {
        List<Room> rooms;

        if (hotelId != null) {
            rooms = roomRepository.findByHotel_IdAndAvailableTrueOrderByTimesBookedAscIdAsc(hotelId);
        } else {
            rooms = roomRepository.findByAvailableTrueOrderByTimesBookedAscIdAsc();
        }

        return rooms.stream()
            .map(this::mapToResponse)
            .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<RoomResponse> search(Long hotelId, java.time.LocalDate startDate,
                                     java.time.LocalDate endDate, boolean recommended) {
        if (startDate == null || endDate == null || !startDate.isBefore(endDate)) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "A valid startDate/endDate interval is required");
        }
        var sort = recommended ? org.springframework.data.domain.Sort.by("timesBooked", "id")
            : org.springframework.data.domain.Sort.unsorted();
        return roomRepository.findFree(hotelId, startDate, endDate,
            java.util.Set.of(ru.mephi.model.ReservationStatus.HELD, ru.mephi.model.ReservationStatus.CONFIRMED),
            org.springframework.data.domain.Pageable.unpaged(sort)).stream().map(this::mapToResponse).toList();
    }

    private RoomResponse mapToResponse(Room room) {
        return new RoomResponse(
            room.getId(),
            room.getHotel().getId(),
            room.getRoomNumber(),
            room.getCapacity(),
            room.getPrice(),
            room.getAvailable(),
            room.getTimesBooked()
        );
    }
}
