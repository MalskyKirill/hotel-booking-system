package ru.mephi.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.mephi.dto.CreateHotelRequest;
import ru.mephi.dto.HotelResponse;
import ru.mephi.exception.HotelHasRoomException;
import ru.mephi.exception.HotelNotFoundException;
import ru.mephi.model.Hotel;
import ru.mephi.repository.HotelRepository;
import ru.mephi.repository.RoomRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class HotelService {

    private final HotelRepository hotelRepository;
    private final RoomRepository roomRepository;

    @Autowired
    public HotelService(HotelRepository hotelRepository, RoomRepository roomRepository) {
        this.hotelRepository = hotelRepository;
        this.roomRepository = roomRepository;
    }

    public HotelResponse create(CreateHotelRequest req) {
        Hotel hotel = new Hotel();
        hotel.setName(req.getName());
        hotel.setAddress(req.getAddress());

        Hotel newHotel = hotelRepository.save(hotel);

        HotelResponse res = new HotelResponse();
        res.setId(newHotel.getId());
        res.setName(newHotel.getName());
        res.setAddress(newHotel.getAddress());
        return res;
    }

    public List<HotelResponse> getAll() {
        List<Hotel> hotels = hotelRepository.findAll();
        List<HotelResponse> responses = new ArrayList<>();

        for (Hotel hotel : hotels) {
            HotelResponse res = new HotelResponse();
            res.setId(hotel.getId());
            res.setName(hotel.getName());
            res.setAddress(hotel.getAddress());
            responses.add(res);
        }

        return responses;
    }

    public HotelResponse findById(Long id) {
        return hotelRepository.findById(id).map(h -> new HotelResponse(h.getId(), h.getName(), h.getAddress())).orElseThrow(() -> new HotelNotFoundException(id));
    }

    @Transactional
    public HotelResponse update(Long id, CreateHotelRequest req) {
        Hotel hotel = hotelRepository.findById(id).orElseThrow(() -> new HotelNotFoundException(id));

        hotel.setName(req.getName().trim());
        hotel.setAddress(req.getAddress().trim());

        return new HotelResponse(hotel.getId(), hotel.getName(), hotel.getAddress());
    }

    @Transactional
    public void delete(Long hotelId) {
        Hotel hotel = hotelRepository.findById(hotelId).orElseThrow(() -> new HotelNotFoundException(hotelId));

        if (roomRepository.existsByHotelId(hotelId)) {
            throw new HotelHasRoomException(hotelId);
        }

        hotelRepository.delete(hotel);
    }
}
