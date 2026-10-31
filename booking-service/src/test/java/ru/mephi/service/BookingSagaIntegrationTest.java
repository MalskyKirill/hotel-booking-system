package ru.mephi.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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

@DataJpaTest(showSql = false, properties = "booking.saga.remote.initial-delay-ms=0")
@Import({BookingService.class, BookingPendingService.class, BookingCreationTransactions.class, BookingSagaTransactions.class, BookingSagaCoordinator.class, HotelCallRetry.class, BookingSagaRecovery.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BookingSagaIntegrationTest {
    @Autowired private BookingService service;
    @Autowired private BookingSagaRecovery recovery;
    @Autowired private BookingSagaTransactions sagaState;
    @MockitoSpyBean private BookingCreationTransactions transactions;
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
        doThrow(new RemoteServiceUncertainException("Offline")).when(hotel).getStatus(any());
        assertEquals(BookingStatus.PENDING, service.create(request, "alice").status());
        var pending = bookings.findByRequestId(request.requestId()).orElseThrow();
        assertEquals(BookingStatus.PENDING, pending.getStatus());
        doAnswer(invocation -> response(invocation.getArgument(0), ReservationStatus.CONFIRMED)).when(hotel).getStatus(any());

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

    @Test
    void businessRefusalCancelsOnlyAfterReleasedMarker() {
        doThrow(new ru.mephi.exception.RemoteBusinessConflictException("occupied")).when(hotel).reserve(any());
        doThrow(new ru.mephi.exception.RemoteReservationNotFoundException(1L)).when(hotel).getStatus(any());
        doAnswer(inv -> response(inv.getArgument(0), ReservationStatus.RELEASED)).when(hotel).release(any());
        BookingResponse cancelled = service.create(request, "alice");
        assertEquals(BookingStatus.CANCELLED, cancelled.status());
        assertEquals(BookingStatus.CANCELLED, bookings.findById(cancelled.id()).orElseThrow().getStatus());
        assertEquals(cancelled, service.create(request, "alice"));
        verify(hotel, times(1)).reserve(any());
        verify(hotel, times(1)).release(any());
    }

    @Test
    void timeoutAfterHeldIsCompensatedAfterBoundedReserveAttempts() {
        doThrow(new RemoteServiceUncertainException("lost reserve response")).when(hotel).reserve(any());
        doAnswer(inv -> response(inv.getArgument(0), ReservationStatus.HELD)).when(hotel).getStatus(any());
        doAnswer(inv -> response(inv.getArgument(0), ReservationStatus.RELEASED)).when(hotel).release(any());
        assertEquals(BookingStatus.CANCELLED, service.create(request, "alice").status());
        verify(hotel, times(3)).reserve(any());
        verify(hotel, times(1)).release(any());
    }

    @Test
    void lostConfirmResponseRecoversConfirmedRemoteInsteadOfCancelling() {
        doThrow(new RemoteServiceUncertainException("lost confirm response")).when(hotel).confirm(any());
        doAnswer(inv -> response(inv.getArgument(0), ReservationStatus.CONFIRMED)).when(hotel).getStatus(any());
        BookingResponse confirmed = service.create(request, "alice");
        assertEquals(BookingStatus.CONFIRMED, confirmed.status());
        assertEquals(BookingStatus.CONFIRMED, bookings.findById(confirmed.id()).orElseThrow().getStatus());
        verify(hotel, times(3)).confirm(any());
        verify(hotel, never()).release(any());
    }

    @Test
    void failedReleasePersistsCompensationAndNextRequestDoesNotReserveAgain() {
        doThrow(new RemoteServiceUncertainException("lost reserve response")).when(hotel).reserve(any());
        doAnswer(inv -> response(inv.getArgument(0), ReservationStatus.HELD)).when(hotel).getStatus(any());
        doThrow(new RemoteServiceUncertainException("release offline")).when(hotel).release(any());
        BookingResponse pending = service.create(request, "alice");
        var saved = bookings.findById(pending.id()).orElseThrow();
        assertEquals(BookingStatus.PENDING, saved.getStatus());
        assertTrue(saved.isSagaCompensating());
        assertEquals(1, saved.getSagaRecoveryAttempts());
        assertTrue(saved.getSagaNextAttemptAt().isAfter(java.time.Instant.now()));
        doAnswer(inv -> response(inv.getArgument(0), ReservationStatus.RELEASED)).when(hotel).getStatus(any());
        assertEquals(BookingStatus.CANCELLED, service.create(request, "alice").status());
        verify(hotel, times(3)).reserve(any());
    }

    @Test
    void confirmWinningRaceWithReleaseIsReconciledAsConfirmed() {
        doThrow(new RemoteServiceUncertainException("lost reserve response")).when(hotel).reserve(any());
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(inv -> response(inv.getArgument(0), reads.incrementAndGet() == 1
            ? ReservationStatus.HELD : ReservationStatus.CONFIRMED)).when(hotel).getStatus(any());
        doThrow(new ru.mephi.exception.RemoteBusinessConflictException("already confirmed")).when(hotel).release(any());
        assertEquals(BookingStatus.CONFIRMED, service.create(request, "alice").status());
    }

    @Test
    void workerCompletesDueBookingWithoutRepeatedUserRequest() {
        BookingResponse pending = pendingService.create(request, "alice");
        makeDue(pending.id());
        recovery.recoverDue();
        assertEquals(BookingStatus.CONFIRMED, bookings.findById(pending.id()).orElseThrow().getStatus());
        recovery.recoverDue();
        verify(hotel, times(1)).reserve(any());
    }

    @Test
    void newCoordinatorResumesPersistedCompensationWithoutReservingAgain() {
        BookingResponse pending = pendingService.create(request, "alice");
        sagaState.beginCompensation(pending.id());
        makeDue(pending.id());
        doAnswer(inv -> response(inv.getArgument(0), ReservationStatus.HELD)).when(hotel).getStatus(any());
        doAnswer(inv -> response(inv.getArgument(0), ReservationStatus.RELEASED)).when(hotel).release(any());
        var restarted = new BookingSagaCoordinator(sagaState, transactions, hotel, new HotelCallRetry(3, 0));
        new BookingSagaRecovery(sagaState, restarted, 20).recoverDue();
        assertEquals(BookingStatus.CANCELLED, bookings.findById(pending.id()).orElseThrow().getStatus());
        verify(hotel, never()).reserve(any());
    }

    @Test
    void workerSkipsNotDueAndTerminalBookings() {
        BookingResponse pending = pendingService.create(request, "alice");
        recovery.recoverDue();
        verifyNoInteractions(hotel);
        transactions.confirm(pending.id());
        makeDue(pending.id());
        recovery.recoverDue();
        verifyNoInteractions(hotel);
    }

    @Test
    void workerRepairsLocalCommitFailureAfterHotelConfirmed() {
        doThrow(new org.springframework.dao.TransientDataAccessResourceException("temporary DB failure"))
            .doCallRealMethod().when(transactions).confirm(anyLong());
        BookingResponse pending = service.create(request, "alice");
        assertEquals(BookingStatus.PENDING, pending.status());
        assertFalse(bookings.findById(pending.id()).orElseThrow().isSagaCompensating());
        makeDue(pending.id());
        doAnswer(inv -> response(inv.getArgument(0), ReservationStatus.CONFIRMED)).when(hotel).reserve(any());
        recovery.recoverDue();
        assertEquals(BookingStatus.CONFIRMED, bookings.findById(pending.id()).orElseThrow().getStatus());
        verify(hotel, times(1)).confirm(any());
        verify(hotel, never()).release(any());
    }

    @Test
    void recoverySelectsOnlyConfiguredBatchInStableOrder() {
        for (int i = 0; i < 3; i++) {
            BookingResponse pending = pendingService.create(new CreateBookingRequest(request.roomId(),
                request.startDate(), request.endDate(), "batch-" + i), "alice");
            makeDue(pending.id());
        }
        var selected = sagaState.due(java.time.Instant.now(), 2);
        assertEquals(2, selected.size());
        assertTrue(selected.getFirst() < selected.getLast());
    }

    private void makeDue(Long id) {
        var booking = bookings.findById(id).orElseThrow();
        booking.setSagaNextAttemptAt(java.time.Instant.EPOCH);
        bookings.save(booking);
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
