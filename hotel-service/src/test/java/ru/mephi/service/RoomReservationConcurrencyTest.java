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
@Import(RoomReservationService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RoomReservationConcurrencyTest {
    @Autowired private RoomReservationService service;
    @Autowired private RoomReservationRepository reservations;
    @Autowired private RoomRepository rooms;
    @Autowired private HotelRepository hotels;
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
