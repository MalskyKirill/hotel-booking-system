package ru.mephi.service;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.mephi.dto.*;
import ru.mephi.model.*;
import ru.mephi.repository.*;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@PreAuthorize("hasRole('ADMIN')")
@Transactional(readOnly = true)
public class RoomStatisticsService {
    private final RoomRepository rooms;
    private final RoomReservationRepository reservations;
    public RoomStatisticsService(RoomRepository rooms, RoomReservationRepository reservations) {
        this.rooms = rooms; this.reservations = reservations;
    }
    public RoomStatisticsPage statistics(Long hotelId, LocalDate startDate, LocalDate endDate, int page, int size) {
        if (startDate == null || endDate == null || !startDate.isBefore(endDate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid date interval is required");
        }
        var pageable = RoomAdminService.pageRequest(page, size);
        var result = hotelId == null ? rooms.findAll(pageable) : rooms.findByHotel_Id(hotelId, pageable);
        var ids = result.getContent().stream().map(Room::getId).toList();
        List<RoomReservation> records = ids.isEmpty() ? List.of() : reservations.findForStatistics(ids, startDate, endDate,
            Set.of(ReservationStatus.HELD, ReservationStatus.CONFIRMED));
        var grouped = records.stream().collect(java.util.stream.Collectors.groupingBy(r -> r.getRoom().getId()));
        long period = ChronoUnit.DAYS.between(startDate, endDate);
        var content = result.getContent().stream().map(room -> {
            long held = 0, confirmed = 0, heldCount = 0, confirmedCount = 0;
            for (RoomReservation reservation : grouped.getOrDefault(room.getId(), List.of())) {
                LocalDate start = reservation.getStartDate().isAfter(startDate) ? reservation.getStartDate() : startDate;
                LocalDate end = reservation.getEndDate().isBefore(endDate) ? reservation.getEndDate() : endDate;
                long nights = ChronoUnit.DAYS.between(start, end);
                if (reservation.getStatus() == ReservationStatus.HELD) { held += nights; heldCount++; }
                else { confirmed += nights; confirmedCount++; }
            }
            return new RoomStatistics(room.getId(), room.getHotel().getId(), room.getRoomNumber(), room.getAvailable(),
                room.getTimesBooked(), period, heldCount, confirmedCount, held, confirmed,
                Math.round(10000.0 * confirmed / period) / 100.0);
        }).toList();
        return new RoomStatisticsPage(content, page, size, result.getTotalElements(), result.getTotalPages());
    }
}
