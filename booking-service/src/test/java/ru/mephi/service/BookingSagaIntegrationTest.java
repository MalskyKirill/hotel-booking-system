package ru.mephi.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.mephi.client.HotelReservationClient;
import ru.mephi.dto.*;
import ru.mephi.exception.RemoteServiceUncertainException;
import ru.mephi.model.BookingStatus;
import ru.mephi.model.Role;
import ru.mephi.model.User;
import ru.mephi.repository.BookingRepository;
import ru.mephi.repository.UserRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DataJpaTest(showSql = false)
@Import({BookingService.class, BookingPendingService.class, BookingCreationTransactions.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BookingSagaIntegrationTest {
    @Autowired private BookingService service;
    @Autowired private BookingPendingService pendingService;
    @Autowired private BookingRepository bookings;
    @Autowired private UserRepository users;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private HotelReservationClient hotel;
    private final CreateBookingRequest request = new CreateBookingRequest(2L,
        LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 3), "saga-request");

    @BeforeEach
    void setUp() {
        bookings.deleteAll();
        users.deleteAll();
        users.save(new User(null, "alice", "unused-test-hash", Role.USER));
        doAnswer(invocation -> {
            CreateReservationRequest remote = invocation.getArgument(0);
            assertCommittedOutsideTransaction(remote);
            return response(remote, ReservationStatus.HELD);
        }).when(hotel).reserve(any());
        when(hotel.confirm(any())).thenAnswer(invocation -> {
            CreateReservationRequest remote = invocation.getArgument(0);
            assertCommittedOutsideTransaction(remote);
            return response(remote, ReservationStatus.CONFIRMED);
        });
    }

    @Test
    void confirmedBookingIsCommittedAndRepeatDoesNotCallHotel() {
        BookingResponse confirmed = service.create(request, "alice");
        assertEquals(BookingStatus.CONFIRMED, confirmed.status());
        assertEquals(BookingStatus.CONFIRMED, bookings.findById(confirmed.id()).orElseThrow().getStatus());
        assertEquals(confirmed, service.create(request, "alice"));
        assertEquals(1, bookings.count());
        verify(hotel, times(1)).reserve(any());
        verify(hotel, times(1)).confirm(any());
    }

    @Test
    void suspendsAmbientTransactionDuringRemoteCalls() {
        BookingResponse confirmed = new TransactionTemplate(transactionManager).execute(status -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            BookingResponse result = service.create(request, "alice");
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            status.setRollbackOnly();
            return result;
        });
        assertNotNull(confirmed);
        assertEquals(BookingStatus.CONFIRMED, bookings.findById(confirmed.id()).orElseThrow().getStatus());
    }

    @Test
    void resumesPendingWithAlreadyConfirmedRemoteReservation() {
        BookingResponse pending = pendingService.create(request, "alice");
        doAnswer(invocation -> {
            CreateReservationRequest remote = invocation.getArgument(0);
            assertCommittedOutsideTransaction(remote);
            return response(remote, ReservationStatus.CONFIRMED);
        }).when(hotel).reserve(any());
        BookingResponse confirmed = service.create(request, "alice");
        assertEquals(pending.id(), confirmed.id());
        assertEquals(BookingStatus.CONFIRMED, confirmed.status());
        assertEquals(1, bookings.count());
        verify(hotel, never()).confirm(any());
    }

    @Test
    void failedAttemptStaysPendingAndRepeatedRequestContinuesSameBooking() {
        doThrow(new RemoteServiceUncertainException("Lost confirmation response")).when(hotel).confirm(any());
        assertThrows(RemoteServiceUncertainException.class, () -> service.create(request, "alice"));
        var pending = bookings.findByRequestId(request.requestId()).orElseThrow();
        assertEquals(BookingStatus.PENDING, pending.getStatus());
        doAnswer(invocation -> response(invocation.getArgument(0), ReservationStatus.CONFIRMED)).when(hotel).reserve(any());

        BookingResponse confirmed = service.create(request, "alice");
        assertEquals(pending.getId(), confirmed.id());
        assertEquals(BookingStatus.CONFIRMED, confirmed.status());
        assertEquals(1, bookings.count());
    }

    @Test
    void concurrentIdenticalRequestsConfirmOneBooking() throws Exception {
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
            for (var future : futures) {
                assertEquals(first, future.get(15, TimeUnit.SECONDS));
            }
            assertEquals(BookingStatus.CONFIRMED, first.status());
            assertEquals(1, bookings.count());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private void assertCommittedOutsideTransaction(CreateReservationRequest remote) {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertTrue(List.of(BookingStatus.PENDING, BookingStatus.CONFIRMED)
            .contains(bookings.findById(remote.bookingId()).orElseThrow().getStatus()));
        assertEquals(request.roomId(), remote.roomId());
        assertEquals(request.startDate(), remote.startDate());
        assertEquals(request.endDate(), remote.endDate());
    }

    private ReservationResponse response(CreateReservationRequest request, ReservationStatus status) {
        return new ReservationResponse(request.bookingId(), request.roomId(), request.startDate(), request.endDate(), status);
    }
}
