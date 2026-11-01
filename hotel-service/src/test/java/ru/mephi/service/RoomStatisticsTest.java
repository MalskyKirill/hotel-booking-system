package ru.mephi.service;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.*;
import ru.mephi.dto.CreateReservationRequest;
import ru.mephi.model.*;
import ru.mephi.repository.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(showSql = false)
@Import({RoomStatisticsService.class, RoomReservationTransactions.class, RoomAdminTest.MethodSecurity.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RoomStatisticsTest {
    @Autowired RoomStatisticsService statistics;
    @Autowired RoomReservationTransactions service;
    @Autowired RoomRepository rooms;
    @Autowired HotelRepository hotels;
    @Autowired RoomReservationRepository records;
    Long roomId;
    @BeforeEach void setUp() {
        RoomAdminTest.role("ADMIN"); records.deleteAll(); rooms.deleteAll(); hotels.deleteAll();
        Hotel hotel = new Hotel(); hotel.setName("Stats"); hotel.setAddress("Test"); hotel = hotels.save(hotel);
        Room room = new Room(); room.setHotel(hotel); room.setRoomNumber("1"); room.setCapacity(1);
        room.setPrice(BigDecimal.TEN); room.setAvailable(true); roomId = rooms.save(room).getId();
    }
    @AfterEach void cleanSecurity() { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    @Test void clipsIntervalsAndSeparatesHeldFromConfirmedAndReleased() {
        var start = LocalDate.of(2027, 1, 1); var end = start.plusDays(7);
        service.reserve(new CreateReservationRequest(1L, roomId, start.minusDays(2), start.plusDays(2))); service.confirm(1L);
        service.reserve(new CreateReservationRequest(2L, roomId, start.plusDays(2), start.plusDays(4)));
        service.reserve(new CreateReservationRequest(3L, roomId, start.plusDays(4), start.plusDays(6))); service.release(3L);
        service.reserve(new CreateReservationRequest(4L, roomId, end, end.plusDays(1))); service.confirm(4L);
        var room = rooms.findById(roomId).orElseThrow(); room.setAvailable(false); rooms.save(room);
        var result = statistics.statistics(null, start, end, 0, 20).content().getFirst();
        assertEquals(7, result.periodNights()); assertEquals(2, result.confirmedNights()); assertEquals(2, result.heldNights());
        assertEquals(1, result.confirmedReservations()); assertEquals(1, result.heldReservations());
        assertEquals(28.57, result.confirmedOccupancyPercent()); assertEquals(2, result.timesBooked());
        assertFalse(result.available());
    }
    @Test void emptyScopeAndInvalidPeriodAreHandled() {
        var start = LocalDate.of(2027, 1, 1);
        assertTrue(statistics.statistics(-1L, start, start.plusDays(1), 0, 20).content().isEmpty());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
            () -> statistics.statistics(null, start, start, 0, 20));
    }
    @Test void nonAdminCannotReadStatistics() {
        RoomAdminTest.role("USER");
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
            () -> statistics.statistics(null, LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 3), 0, 20));
    }
}
