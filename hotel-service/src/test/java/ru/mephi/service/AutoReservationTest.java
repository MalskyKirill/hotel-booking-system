package ru.mephi.service;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.*;
import ru.mephi.dto.*;
import ru.mephi.model.*;
import ru.mephi.repository.*;
import ru.mephi.exception.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(showSql = false)
@Import({AutoReservationService.class, AutoReservationTransactions.class, RoomReservationTransactions.class, RoomService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AutoReservationTest {
    @Autowired AutoReservationService service;
    @Autowired AutoReservationOperationRepository operations;
    @Autowired RoomReservationRepository reservations;
    @Autowired RoomRepository rooms;
    @Autowired HotelRepository hotels;
    @Autowired RoomService search;
    Long hotelId;
    List<Long> ids;

    @BeforeEach
    void setUp() {
        reservations.deleteAll(); operations.deleteAll(); rooms.deleteAll(); hotels.deleteAll();
        Hotel hotel = new Hotel(); hotel.setName("Auto"); hotel.setAddress("Test");
        hotelId = hotels.save(hotel).getId();
        ids = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Room room = new Room(); room.setHotel(hotel); room.setRoomNumber("R" + i);
            room.setCapacity(1); room.setPrice(BigDecimal.TEN); room.setAvailable(true);
            ids.add(rooms.save(room).getId());
        }
    }
    @Test
    void picksLeastBookedWithIdTieBreakAndKeepsSelectionOnRepeat() {
        var request = request(1L);
        assertEquals(ids.getFirst(), service.reserve(request).roomId());
        service.confirm(request); service.confirm(request);
        assertEquals(1, rooms.findById(ids.getFirst()).orElseThrow().getTimesBooked());
        assertEquals(ids.getFirst(), service.reserve(request).roomId());
        var later = new AutoReservationRequest(2L, hotelId, request.endDate(), request.endDate().plusDays(2));
        assertEquals(ids.getLast(), service.reserve(later).roomId());
        assertEquals(ids.getLast(), search.search(hotelId, later.endDate(), later.endDate().plusDays(1), true).getFirst().getId());
    }
    @Test
    void concurrentBookingsUseDifferentRooms() throws Exception {
        var result = race(() -> service.reserve(request(1L)), () -> service.reserve(request(2L)));
        assertNotEquals(result.getFirst().roomId(), result.getLast().roomId());
        assertEquals(2, reservations.count());
        assertThrows(NoAvailableRoomException.class, () -> service.reserve(request(3L)));
    }
    @Test
    void concurrentRepeatsUseOneRoomAndOneReservation() throws Exception {
        var result = race(() -> service.reserve(request(1L)), () -> service.reserve(request(1L)));
        assertEquals(result.getFirst(), result.getLast());
        assertEquals(1, reservations.count());
    }
    @Test
    void cancellationBeforeSelectionPreventsLateReserve() {
        var request = request(1L);
        var released = service.release(request);
        assertEquals(ReservationStatus.RELEASED, released.status());
        assertNull(released.roomId());
        assertEquals(released, service.reserve(request));
        assertEquals(released, service.getStatus(request));
        assertThrows(InvalidReservationStateException.class, () -> service.confirm(request));
        assertEquals(0, reservations.count());
    }
    @Test
    void concurrentReserveAndCompensationFinishReleased() throws Exception {
        var request = request(1L);
        race(() -> service.reserve(request), () -> service.release(request));
        assertEquals(ReservationStatus.RELEASED, service.getStatus(request).status());
        assertEquals(ReservationStatus.RELEASED, service.reserve(request).status());
    }
    @Test
    void confirmedReservationCannotBeCompensated() {
        var request = request(1L);
        service.reserve(request); service.confirm(request);
        assertThrows(InvalidReservationStateException.class, () -> service.release(request));
        assertEquals(ReservationStatus.CONFIRMED, service.getStatus(request).status());
    }
    @Test
    void filtersOperationalAvailabilityAndHotelAndChecksPayload() {
        var room = rooms.findById(ids.getFirst()).orElseThrow(); room.setAvailable(false); rooms.save(room);
        assertEquals(ids.getLast(), service.reserve(request(1L)).roomId());
        assertThrows(IdempotencyConflictException.class, () -> service.reserve(
            new AutoReservationRequest(1L, null, request(1L).startDate(), request(1L).endDate())));
        assertThrows(NoAvailableRoomException.class, () -> service.reserve(
            new AutoReservationRequest(2L, hotelId + 100, request(1L).startDate(), request(1L).endDate())));
    }
    private AutoReservationRequest request(Long id) {
        return new AutoReservationRequest(id, hotelId, LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 3));
    }
    private List<ReservationResponse> race(Callable<ReservationResponse> first, Callable<ReservationResponse> second) throws Exception {
        var barrier = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return first.call(); });
            var b = executor.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return second.call(); });
            return List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
    }
}
