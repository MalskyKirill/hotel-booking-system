package ru.mephi.controller;

import org.springframework.web.bind.annotation.*;
import ru.mephi.dto.RoomStatisticsPage;
import ru.mephi.service.RoomStatisticsService;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/admin/rooms/statistics")
public class RoomStatisticsController {
    private final RoomStatisticsService statistics;
    public RoomStatisticsController(RoomStatisticsService statistics) { this.statistics = statistics; }
    @GetMapping public RoomStatisticsPage statistics(@RequestParam(required = false) Long hotelId,
        @RequestParam LocalDate startDate, @RequestParam LocalDate endDate,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return statistics.statistics(hotelId, startDate, endDate, page, size);
    }
}
