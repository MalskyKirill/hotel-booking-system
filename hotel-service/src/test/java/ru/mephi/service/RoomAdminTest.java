package ru.mephi.service;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.*;
import ru.mephi.dto.*;
import ru.mephi.model.*;
import ru.mephi.repository.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(showSql = false)
@Import({RoomAdminService.class, RoomService.class, RoomReservationTransactions.class, RoomAdminTest.MethodSecurity.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RoomAdminTest {
    @TestConfiguration @EnableMethodSecurity static class MethodSecurity { }
    @Autowired RoomAdminService admin;
    @Autowired RoomService roomsService;
    @Autowired RoomReservationTransactions reservations;
    @Autowired RoomRepository rooms;
    @Autowired HotelRepository hotels;
    @Autowired RoomReservationRepository records;
    Long roomId;
    @BeforeEach void setUp() {
        role("ADMIN"); records.deleteAll(); rooms.deleteAll(); hotels.deleteAll();
        Hotel hotel = new Hotel(); hotel.setName("Admin test"); hotel.setAddress("Test"); hotel = hotels.save(hotel);
        Room room = new Room(); room.setHotel(hotel); room.setRoomNumber("1"); room.setCapacity(1);
        room.setPrice(BigDecimal.TEN); room.setAvailable(true); roomId = rooms.save(room).getId();
    }
    @AfterEach void cleanSecurity() { SecurityContextHolder.clearContext(); }
    @Test void updateDoesNotChangeIdentityHotelOrCounter() {
        reservations.reserve(request()); reservations.confirm(1L);
        Long hotelId = admin.get(roomId).getHotelId();
        var updated = admin.update(roomId, new UpdateRoomRequest(" New ", 3, new BigDecimal("99.50"), false));
        assertEquals("New", updated.getRoomNumber()); assertEquals(hotelId, updated.getHotelId());
        assertEquals(1, updated.getTimesBooked()); assertFalse(updated.getAvailable());
        assertEquals(ReservationStatus.CONFIRMED, records.findByBookingId(1L).orElseThrow().getStatus());
        assertEquals(1, admin.list(null, 0, 20).totalElements());
    }
    @Test void unavailableRoomCannotReceiveNewBookingAndHistoryPreventsDeletion() {
        admin.update(roomId, new UpdateRoomRequest("1", 1, BigDecimal.TEN, false));
        assertThrows(ru.mephi.exception.RoomUnavailableException.class, () -> reservations.reserve(request()));
        admin.update(roomId, new UpdateRoomRequest("1", 1, BigDecimal.TEN, true));
        reservations.reserve(request()); reservations.release(request());
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> admin.delete(roomId));
    }
    @Test void unusedRoomCanBeDeleted() {
        admin.delete(roomId);
        assertThrows(ru.mephi.exception.RoomNotFoundException.class, () -> admin.get(roomId));
    }
    @Test void nonAdminCannotUseServiceEvenDirectly() {
        role("USER");
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> admin.get(roomId));
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> admin.delete(roomId));
    }
    @Test void duplicateNumberInSameHotelIsConflict() {
        var room = new Room(); room.setHotel(hotels.findAll().getFirst()); room.setRoomNumber("2");
        room.setCapacity(1); room.setPrice(BigDecimal.TEN); room.setAvailable(true); rooms.save(room);
        assertThrows(ru.mephi.exception.RoomAlreadyExistsException.class,
            () -> admin.update(roomId, new UpdateRoomRequest("2", 1, BigDecimal.TEN, true)));
    }
    @Test void demoHotelsDoNotOverwriteExistingDataAndAreIdempotent() throws Exception {
        var seed = new ru.mephi.config.DemoHotelData(hotels, rooms);
        seed.run(new org.springframework.boot.DefaultApplicationArguments());
        assertEquals(1, hotels.count()); assertEquals(1, rooms.count());
        rooms.deleteAll(); hotels.deleteAll();
        seed.run(new org.springframework.boot.DefaultApplicationArguments());
        assertEquals(2, hotels.count()); assertEquals(6, rooms.count());
        assertEquals(4, rooms.findByAvailableTrue().size());
        seed.run(new org.springframework.boot.DefaultApplicationArguments());
        assertEquals(6, rooms.count());
    }

    private CreateReservationRequest request() {
        return new CreateReservationRequest(1L, roomId, LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 3));
    }
    static void role(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin", "unused",
            List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
