package ru.mephi.service;

import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.mephi.dto.*;
import ru.mephi.exception.*;
import ru.mephi.model.Room;
import ru.mephi.repository.*;

@Service
@PreAuthorize("hasRole('ADMIN')")
@Transactional(readOnly = true)
public class RoomAdminService {
    private final RoomRepository rooms;
    private final RoomReservationRepository reservations;
    public RoomAdminService(RoomRepository rooms, RoomReservationRepository reservations) {
        this.rooms = rooms; this.reservations = reservations;
    }
    public RoomPage list(Long hotelId, int page, int size) {
        var pageable = pageRequest(page, size);
        var result = hotelId == null ? rooms.findAll(pageable) : rooms.findByHotel_Id(hotelId, pageable);
        return new RoomPage(result.map(this::map).getContent(), page, size, result.getTotalElements(), result.getTotalPages());
    }
    public RoomResponse get(Long id) { return map(rooms.findById(id).orElseThrow(() -> new RoomNotFoundException(id))); }
    @Transactional
    public RoomResponse update(Long id, UpdateRoomRequest request) {
        Room room = rooms.findByIdForUpdate(id).orElseThrow(() -> new RoomNotFoundException(id));
        String number = request.roomNumber().trim();
        if (!number.equals(room.getRoomNumber()) && rooms.existsByHotel_IdAndRoomNumber(room.getHotel().getId(), number)) {
            throw new RoomAlreadyExistsException(room.getHotel().getId(), number);
        }
        room.setRoomNumber(number); room.setCapacity(request.capacity());
        room.setPrice(request.price()); room.setAvailable(request.available());
        return map(room);
    }
    @Transactional
    public void delete(Long id) {
        Room room = rooms.findByIdForUpdate(id).orElseThrow(() -> new RoomNotFoundException(id));
        if (reservations.existsByRoom_Id(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Room has reservation history; disable it instead");
        }
        rooms.delete(room);
    }
    static PageRequest pageRequest(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid page bounds");
        return PageRequest.of(page, size, Sort.by("id"));
    }
    private RoomResponse map(Room room) {
        return new RoomResponse(room.getId(), room.getHotel().getId(), room.getRoomNumber(), room.getCapacity(),
            room.getPrice(), room.getAvailable(), room.getTimesBooked());
    }
}
