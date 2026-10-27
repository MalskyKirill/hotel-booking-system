package ru.mephi.controller;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.mephi.dto.CreateHotelRequest;
import ru.mephi.dto.HotelResponse;
import ru.mephi.service.HotelService;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/hotels")
public class HotelController {
    private final HotelService hotelService;

    public HotelController(HotelService hotelService) {
        this.hotelService = hotelService;
    }

    @PostMapping
    public ResponseEntity<HotelResponse> create(@Valid @RequestBody CreateHotelRequest req) {
        HotelResponse res = hotelService.create(req);

        return ResponseEntity.created(URI.create("/api/hotels/" + res.getId())).body(res);
    }

    @GetMapping
    public ResponseEntity<List<HotelResponse>> getAll() {
        List<HotelResponse> res = hotelService.getAll();

        return ResponseEntity.ok(res);
    }

    @GetMapping("/{id}")
    public HotelResponse getById(@PathVariable Long id) {
        return hotelService.findById(id);
    }

    @PutMapping("/{id}")
    public HotelResponse updateById(@PathVariable Long id, @Valid @RequestBody CreateHotelRequest req) {
        return hotelService.update(id, req);
    }
}
