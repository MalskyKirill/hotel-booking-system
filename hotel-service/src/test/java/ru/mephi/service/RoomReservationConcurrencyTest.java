package ru.mephi.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.mephi.dto.CreateReservationRequest;
import ru.mephi.dto.ReservationResponse;
import ru.mephi.exception.InvalidReservationStateException;
import ru.mephi.exception.ReservationConflictException;
import ru.mephi.model.*;
import ru.mephi.repository.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(showSql = false)
@Import({RoomReservationService.class, RoomReservationTransactions.class, RoomService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RoomReservationConcurrencyTest {
    @Autowired private RoomReservationService service;
    @Autowired private RoomReservationRepository reservations;
    @Autowired private RoomRepository rooms;
    @Autowired private HotelRepository hotels;
    @Autowired private RoomService roomService;
    private Long roomId;

    @BeforeEach
    void setUp() {
        reservations.deleteAll();
        rooms.deleteAll();
        hotels.deleteAll();
        Hotel hotel = new Hotel();
        hotel.setName("Concurrency test");
        hotel.setAddress("Test address");
        hotel = hotels.save(hotel);
        Room room = new Room();
        room.setHotel(hotel);
        room.setRoomNumber("1");
        room.setAvailable(true);
        room.setCapacity(1);
        room.setPrice(BigDecimal.TEN);
        room.setTimesBooked(0);
        roomId = rooms.save(room).getId();
    }

    @Test
    void overlappingConcurrentReservesHaveOneWinner() throws Exception {
        List<Object> results = race(() -> service.reserve(request(1L)), () -> service.reserve(request(2L)));
        assertEquals(1, results.stream().filter(ReservationResponse.class::isInstance).count());
        assertEquals(1, results.stream().filter(ReservationConflictException.class::isInstance).count());
        assertEquals(1, reservations.count());
    }

    @Test
    void identicalConcurrentReservesReturnSameReservation() throws Exception {
        List<Object> results = race(() -> service.reserve(request(1L)), () -> service.reserve(request(1L)));
        assertInstanceOf(ReservationResponse.class, results.getFirst());
        assertEquals(results.getFirst(), results.getLast());
        assertEquals(1, reservations.count());
    }

    @Test
    void concurrentConfirmIncrementsCounterOnce() throws Exception {
        service.reserve(request(1L));
        List<Object> results = race(() -> service.confirm(1L), () -> service.confirm(1L));
        for (Object result : results) {
            assertEquals(ReservationStatus.CONFIRMED, assertInstanceOf(ReservationResponse.class, result).status());
        }
        assertEquals(1, rooms.findById(roomId).orElseThrow().getTimesBooked());
    }

    @Test
    void confirmAndReleaseHaveOneTerminalWinner() throws Exception {
        service.reserve(request(1L));
        List<Object> results = race(() -> service.confirm(1L), () -> service.release(1L));
        assertEquals(1, results.stream().filter(ReservationResponse.class::isInstance).count());
        assertEquals(1, results.stream().filter(InvalidReservationStateException.class::isInstance).count());
        ReservationStatus status = service.getByBookingId(1L).status();
        assertTrue(status == ReservationStatus.CONFIRMED || status == ReservationStatus.RELEASED);
        assertEquals(status == ReservationStatus.CONFIRMED ? 1 : 0,
            rooms.findById(roomId).orElseThrow().getTimesBooked());
    }

    @Test
    void adjacentDatesAndReleasedSlotsCanBeReserved() {
        CreateReservationRequest first = request(1L);
        service.reserve(first);
        service.reserve(new CreateReservationRequest(2L, roomId, first.endDate(), first.endDate().plusDays(1)));
        service.release(1L);
        assertEquals(ReservationStatus.RELEASED, service.release(1L).status());
        assertEquals(ReservationStatus.HELD, service.reserve(request(3L)).status());
    }

    @Test
    void compensationBeforeReservePermanentlyReleasesOperation() {
        CreateReservationRequest request = request(1L);
        assertEquals(ReservationStatus.RELEASED, service.release(request).status());
        assertEquals(ReservationStatus.RELEASED, service.reserve(request).status());
        assertEquals(ReservationStatus.RELEASED, service.release(request).status());
        assertThrows(InvalidReservationStateException.class, () -> service.confirm(1L));
        assertEquals(ReservationStatus.HELD, service.reserve(request(2L)).status());
    }

    @Test
    void reserveRacingWithCompensationAlwaysEndsReleased() throws Exception {
        CreateReservationRequest request = request(1L);
        List<Object> results = race(() -> service.reserve(request), () -> service.release(request));
        for (Object result : results) {
            assertInstanceOf(ReservationResponse.class, result);
        }
        assertEquals(ReservationStatus.RELEASED, service.getByBookingId(1L).status());
        assertEquals(0, rooms.findById(roomId).orElseThrow().getTimesBooked());
    }

    @Test
    void sameBookingForDifferentRoomsProducesIdempotencyConflict() throws Exception {
        Room second = new Room();
        second.setHotel(hotels.findAll().getFirst());
        second.setRoomNumber("2");
        second.setAvailable(true);
        second.setCapacity(1);
        second.setPrice(BigDecimal.TEN);
        Long secondId = rooms.save(second).getId();
        CreateReservationRequest first = request(1L);
        CreateReservationRequest other = new CreateReservationRequest(1L, secondId, first.startDate(), first.endDate());
        List<Object> results = race(() -> service.reserve(first), () -> service.reserve(other));
        assertEquals(1, results.stream().filter(ReservationResponse.class::isInstance).count());
        assertEquals(1, results.stream().filter(ru.mephi.exception.IdempotencyConflictException.class::isInstance).count());
        assertEquals(1, reservations.count());
    }

    @Test
    void compensationDoesNotReleaseConfirmedReservationOrAcceptChangedDates() {
        CreateReservationRequest request = request(1L);
        service.reserve(request);
        service.confirm(1L);
        assertThrows(InvalidReservationStateException.class, () -> service.release(request));
        assertEquals(ReservationStatus.CONFIRMED, service.getByBookingId(1L).status());
        CreateReservationRequest changed = new CreateReservationRequest(1L, roomId,
            request.startDate(), request.endDate().plusDays(1));
        assertThrows(ru.mephi.exception.IdempotencyConflictException.class, () -> service.release(changed));
    }

    @Test
    void dateSearchExcludesHeldAndConfirmedButAllowsReleasedAndAdjacentDates() {
        var req = request(1L);
        service.reserve(req);
        assertTrue(roomService.search(null, req.startDate(), req.endDate(), true).isEmpty());
        assertEquals(1, roomService.search(null, req.endDate(), req.endDate().plusDays(1), true).size());
        service.release(1L);
        assertEquals(roomId, roomService.search(null, req.startDate(), req.endDate(), true).getFirst().getId());
        service.reserve(request(2L));
        service.confirm(2L);
        assertTrue(roomService.search(null, req.startDate(), req.endDate(), false).isEmpty());
    }

    @Test
    void searchRejectsEmptyOrReversedDates() {
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
            () -> roomService.search(null, null, request(1L).endDate(), true));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
            () -> roomService.search(null, request(1L).endDate(), request(1L).startDate(), true));
    }

    @Test
    void explicitCancellationReleasesConfirmedSlotWithoutDecrementingCounter() {
        var request = request(1L);
        service.reserve(request); service.confirm(1L);
        assertEquals(ReservationStatus.RELEASED, service.cancel(request).status());
        assertEquals(ReservationStatus.RELEASED, service.cancel(request).status());
        assertEquals(1, rooms.findById(roomId).orElseThrow().getTimesBooked());
        assertEquals(ReservationStatus.HELD, service.reserve(request(2L)).status());
    }

    @Test
    void explicitCancellationBeforeReserveBlocksLateRequest() {
        var request = request(1L);
        assertEquals(ReservationStatus.RELEASED, service.cancel(request).status());
        assertEquals(ReservationStatus.RELEASED, service.reserve(request).status());
        assertThrows(InvalidReservationStateException.class, () -> service.confirm(1L));
    }

    @Test
    void cancellationRacingWithConfirmAlwaysEndsReleased() throws Exception {
        var request = request(1L); service.reserve(request);
        var results = race(() -> service.confirm(1L), () -> service.cancel(request));
        for (Object result : results) {
            assertTrue(result instanceof ReservationResponse || result instanceof InvalidReservationStateException);
        }
        assertEquals(ReservationStatus.RELEASED, service.getByBookingId(1L).status());
        assertTrue(rooms.findById(roomId).orElseThrow().getTimesBooked() <= 1);
    }

    private CreateReservationRequest request(Long bookingId) {
        return new CreateReservationRequest(bookingId, roomId,
            LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 3));
    }

    private List<Object> race(Callable<?> first, Callable<?> second) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> futures = List.of(executor.submit(() -> invoke(barrier, first)),
                executor.submit(() -> invoke(barrier, second)));
            return List.of(futures.getFirst().get(15, TimeUnit.SECONDS), futures.getLast().get(15, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private Object invoke(CyclicBarrier barrier, Callable<?> action) throws Exception {
        barrier.await(5, TimeUnit.SECONDS);
        try {
            return action.call();
        } catch (RuntimeException ex) {
            return ex;
        }
    }
}
