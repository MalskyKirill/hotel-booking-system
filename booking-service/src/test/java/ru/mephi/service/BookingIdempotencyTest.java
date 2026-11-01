package ru.mephi.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.mephi.dto.BookingResponse;
import ru.mephi.dto.CreateBookingRequest;
import ru.mephi.exception.IdempotencyConflictException;
import ru.mephi.model.Role;
import ru.mephi.model.User;
import ru.mephi.repository.BookingRepository;
import ru.mephi.repository.UserRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(showSql = false)
@Import({BookingPendingService.class, BookingCreationTransactions.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BookingIdempotencyTest {

    @Autowired
    private BookingPendingService service;
    @Autowired
    private BookingCreationTransactions transactions;
    @Autowired
    private BookingRepository bookings;
    @Autowired
    private UserRepository users;

    @BeforeEach
    void setUp() {
        bookings.deleteAll();
        users.deleteAll();
        users.save(new User(null, "alice", "unused-test-hash", Role.USER));
        users.save(new User(null, "bob", "unused-test-hash", Role.USER));
    }

    @Test
    void concurrentIdenticalRequestsReturnOneCommittedBooking() throws Exception {
        CreateBookingRequest request = request(1L);
        int count = 8;
        CyclicBarrier barrier = new CyclicBarrier(count);
        ExecutorService executor = Executors.newFixedThreadPool(count);
        try {
            List<Future<BookingResponse>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                futures.add(executor.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return service.create(request, "alice");
                }));
            }
            BookingResponse first = futures.getFirst().get(15, TimeUnit.SECONDS);
            for (Future<BookingResponse> future : futures) {
                assertEquals(first, future.get(15, TimeUnit.SECONDS));
            }
            assertNotNull(first.createdAt());
            assertEquals(1, bookings.count());
            assertEquals(first, service.create(request, "alice"));
        } finally {
            stop(executor);
        }
    }

    @Test
    void concurrentDifferentPayloadsHaveOneWinnerAndOneConflict() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<BookingResponse>> futures = new ArrayList<>();
            for (long roomId = 1; roomId <= 2; roomId++) {
                CreateBookingRequest request = request(roomId);
                futures.add(executor.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return service.create(request, "alice");
                }));
            }
            int successes = 0;
            int conflicts = 0;
            for (Future<BookingResponse> future : futures) {
                try {
                    future.get(15, TimeUnit.SECONDS);
                    successes++;
                } catch (ExecutionException ex) {
                    assertInstanceOf(IdempotencyConflictException.class, ex.getCause());
                    conflicts++;
                }
            }
            assertEquals(1, successes);
            assertEquals(1, conflicts);
            assertEquals(1, bookings.count());
        } finally {
            stop(executor);
        }
    }

    @Test
    void sameKeyDoesNotReturnAnotherUsersBooking() {
        service.create(request(1L), "alice");

        assertThrows(IdempotencyConflictException.class, () -> service.create(request(1L), "bob"));
        assertEquals(1, bookings.count());
    }

    @Test
    void changedDatesConflictWithExistingRequest() {
        CreateBookingRequest original = request(1L);
        service.create(original, "alice");
        CreateBookingRequest changed = new CreateBookingRequest(1L, original.startDate(),
            original.endDate().plusDays(1), original.requestId());

        assertThrows(IdempotencyConflictException.class, () -> service.create(changed, "alice"));
        assertEquals(1, bookings.count());
    }

    @Test
    void confirmationIsCommittedAndIdempotent() {
        BookingResponse pending = service.create(request(1L), "alice");
        BookingResponse confirmed = transactions.confirm(pending.id());
        assertEquals(ru.mephi.model.BookingStatus.CONFIRMED, confirmed.status());
        assertEquals(confirmed, transactions.confirm(pending.id()));
        assertEquals(confirmed.status(), bookings.findById(pending.id()).orElseThrow().getStatus());
        assertEquals(pending.createdAt(), confirmed.createdAt());
    }

    @Test
    void confirmationCannotReviveCancelledBooking() {
        BookingResponse pending = service.create(request(1L), "alice");
        var booking = bookings.findById(pending.id()).orElseThrow();
        booking.setStatus(ru.mephi.model.BookingStatus.CANCELLED);
        bookings.save(booking);
        assertEquals(ru.mephi.model.BookingStatus.CANCELLED, transactions.confirm(pending.id()).status());
        assertEquals(ru.mephi.model.BookingStatus.CANCELLED,
            bookings.findById(pending.id()).orElseThrow().getStatus());
    }

    private CreateBookingRequest request(Long roomId) {
        return new CreateBookingRequest(roomId, LocalDate.of(2027, 1, 1),
            LocalDate.of(2027, 1, 2), "same-request");
    }

    private void stop(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
}
