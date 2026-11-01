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
@Import({BookingService.class, BookingPendingService.class, BookingCreationTransactions.class, BookingSagaTransactions.class, BookingSagaCoordinator.class, HotelCallRetry.class, BookingSagaRecovery.class, BookingCancellationService.class, BookingQueryService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BookingSagaIntegrationTest {
    @Autowired private BookingService service;
    @Autowired private BookingSagaRecovery recovery;
    @MockitoSpyBean private BookingSagaTransactions sagaState;
    @Autowired private BookingCancellationService cancellations;
    @Autowired private BookingQueryService queries;
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

    @Test
    void automaticSelectionIsPersistedBeforeConfirmAndIgnoresRoomOnRepeat() {
        var auto = new CreateBookingRequest(-99L, request.startDate(), request.endDate(), "auto-1", true, 5L);
        doAnswer(inv -> {
            AutoReservationRequest selected = inv.getArgument(0);
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertNull(bookings.findById(selected.bookingId()).orElseThrow().getRoomId());
            return autoResponse(selected, 7L, ReservationStatus.HELD);
        }).when(hotel).reserveAuto(any());
        doAnswer(inv -> {
            AutoReservationRequest selected = inv.getArgument(0);
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(7L, bookings.findById(selected.bookingId()).orElseThrow().getRoomId());
            return autoResponse(selected, 7L, ReservationStatus.CONFIRMED);
        }).when(hotel).confirmAuto(any());
        BookingResponse confirmed = service.create(auto, "alice");
        assertEquals(BookingStatus.CONFIRMED, confirmed.status());
        assertEquals(7L, confirmed.roomId());
        assertEquals(confirmed, service.create(new CreateBookingRequest(999L, auto.startDate(), auto.endDate(),
            auto.requestId(), true, 5L), "alice"));
        assertThrows(ru.mephi.exception.IdempotencyConflictException.class, () -> service.create(
            new CreateBookingRequest(null, auto.startDate(), auto.endDate(), auto.requestId(), true, 6L), "alice"));
        verify(hotel, times(1)).reserveAuto(any());
        verify(hotel, never()).reserve(any());
    }

    @Test
    void automaticSelectionTimeoutRecoversChosenRoomBeforeCompensation() {
        var auto = new CreateBookingRequest(null, request.startDate(), request.endDate(), "auto-timeout", true, null);
        doThrow(new RemoteServiceUncertainException("Lost selection response")).when(hotel).reserveAuto(any());
        doAnswer(inv -> autoResponse(inv.getArgument(0), 7L, ReservationStatus.HELD)).when(hotel).getAutoStatus(any());
        doAnswer(inv -> autoResponse(inv.getArgument(0), 7L, ReservationStatus.RELEASED)).when(hotel).releaseAuto(any());
        BookingResponse cancelled = service.create(auto, "alice");
        assertEquals(BookingStatus.CANCELLED, cancelled.status());
        assertEquals(7L, cancelled.roomId());
        verify(hotel, times(3)).reserveAuto(any());
    }

    @Test
    void unavailableAutomaticSelectionCanBeCancelledWithoutRoom() {
        var auto = new CreateBookingRequest(null, request.startDate(), request.endDate(), "auto-empty", true, null);
        doThrow(new ru.mephi.exception.RemoteBusinessConflictException("NO_AVAILABLE_ROOM", "empty"))
            .when(hotel).reserveAuto(any());
        doThrow(new ru.mephi.exception.RemoteReservationNotFoundException(1L)).when(hotel).getAutoStatus(any());
        doAnswer(inv -> autoResponse(inv.getArgument(0), null, ReservationStatus.RELEASED)).when(hotel).releaseAuto(any());
        BookingResponse cancelled = service.create(auto, "alice");
        assertEquals(BookingStatus.CANCELLED, cancelled.status());
        assertNull(cancelled.roomId());
        assertEquals(cancelled, service.create(auto, "alice"));
        verify(hotel, times(1)).reserveAuto(any());
    }

    @Test
    void workerRecoversAutomaticSelectionFromPersistedModeAndScope() {
        var auto = new CreateBookingRequest(null, request.startDate(), request.endDate(), "auto-defer", true, 5L);
        doThrow(new RemoteServiceUncertainException("Offline")).when(hotel).reserveAuto(any());
        doThrow(new RemoteServiceUncertainException("Offline")).when(hotel).getAutoStatus(any());
        BookingResponse pending = service.create(auto, "alice");
        assertEquals(BookingStatus.PENDING, pending.status());
        assertNull(pending.roomId());
        doAnswer(inv -> {
            AutoReservationRequest selected = inv.getArgument(0);
            assertEquals(5L, selected.hotelId());
            return autoResponse(selected, 7L, ReservationStatus.CONFIRMED);
        }).when(hotel).getAutoStatus(any());
        makeDue(pending.id());
        recovery.recoverDue();
        var saved = bookings.findById(pending.id()).orElseThrow();
        assertEquals(BookingStatus.CONFIRMED, saved.getStatus());
        assertEquals(7L, saved.getRoomId());
        verify(hotel, times(3)).reserveAuto(any());
    }

    private ReservationResponse autoResponse(AutoReservationRequest request, Long roomId, ReservationStatus status) {
        return new ReservationResponse(request.bookingId(), roomId, request.startDate(), request.endDate(), status);
    }

    @Test
    void ownerCancellationIsIdempotentAndForeignOwnerCannotCancel() {
        BookingResponse confirmed = service.create(request, "alice");
        assertThrows(ru.mephi.exception.BookingNotFoundException.class,
            () -> cancellations.cancel(confirmed.id(), "bob"));
        verify(hotel, never()).cancel(any());
        doAnswer(inv -> {
            CreateReservationRequest remote = inv.getArgument(0);
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(bookings.findById(remote.bookingId()).orElseThrow().isCancellationRequested());
            return response(remote, ReservationStatus.RELEASED);
        }).when(hotel).cancel(any());
        BookingResponse cancelled = cancellations.cancel(confirmed.id(), "alice");
        assertEquals(BookingStatus.CANCELLED, cancelled.status());
        assertFalse(cancelled.cancellationRequested());
        assertEquals(cancelled, cancellations.cancel(confirmed.id(), "alice"));
        assertEquals(cancelled, queries.get(confirmed.id(), "alice"));
        verify(hotel, times(1)).cancel(any());
        verify(hotel, never()).release(any());
    }

    @Test
    void cancellationBeforeAutomaticSelectionDoesNotStartReservation() {
        var auto = new CreateBookingRequest(null, request.startDate(), request.endDate(), "cancel-auto", true, null);
        BookingResponse pending = pendingService.create(auto, "alice");
        doAnswer(inv -> autoResponse(inv.getArgument(0), null, ReservationStatus.RELEASED)).when(hotel).cancelAuto(any());
        BookingResponse cancelled = cancellations.cancel(pending.id(), "alice");
        assertEquals(BookingStatus.CANCELLED, cancelled.status());
        assertNull(cancelled.roomId());
        verify(hotel, never()).reserveAuto(any());
    }

    @Test
    void unavailableCancellationIsVisibleAndRecoveredWithoutUserRetry() {
        BookingResponse confirmed = service.create(request, "alice");
        doThrow(new RemoteServiceUncertainException("offline")).when(hotel).cancel(any());
        doThrow(new RemoteServiceUncertainException("offline")).when(hotel).getStatus(any());
        BookingResponse waiting = cancellations.cancel(confirmed.id(), "alice");
        assertEquals(BookingStatus.CONFIRMED, waiting.status());
        assertTrue(waiting.cancellationRequested());
        assertTrue(queries.get(confirmed.id(), "alice").cancellationRequested());
        doAnswer(inv -> response(inv.getArgument(0), ReservationStatus.RELEASED)).when(hotel).cancel(any());
        makeDue(confirmed.id()); recovery.recoverDue();
        var cancelled = queries.get(confirmed.id(), "alice");
        assertEquals(BookingStatus.CANCELLED, cancelled.status());
        assertFalse(cancelled.cancellationRequested());
        verify(hotel, times(4)).cancel(any());
    }

    @Test
    void lostCancellationResponseIsResolvedByReleasedState() {
        BookingResponse confirmed = service.create(request, "alice");
        doThrow(new RemoteServiceUncertainException("lost response")).when(hotel).cancel(any());
        doAnswer(inv -> response(inv.getArgument(0), ReservationStatus.RELEASED)).when(hotel).getStatus(any());
        assertEquals(BookingStatus.CANCELLED, cancellations.cancel(confirmed.id(), "alice").status());
        verify(hotel, times(3)).cancel(any());
    }

    @Test
    void localCommitFailureAfterRemoteCancellationRemainsRecoverable() {
        BookingResponse confirmed = service.create(request, "alice");
        doAnswer(inv -> response(inv.getArgument(0), ReservationStatus.RELEASED)).when(hotel).cancel(any());
        doThrow(new org.springframework.dao.TransientDataAccessResourceException("temporary DB failure"))
            .doCallRealMethod().when(sagaState).cancel(confirmed.id());
        assertTrue(cancellations.cancel(confirmed.id(), "alice").cancellationRequested());
        makeDue(confirmed.id()); recovery.recoverDue();
        assertEquals(BookingStatus.CANCELLED, queries.get(confirmed.id(), "alice").status());
    }

    @Test
    void delayedForwardConfirmationCannotReviveCancelledBooking() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var proceed = new java.util.concurrent.CountDownLatch(1);
        doAnswer(inv -> {
            entered.countDown();
            assertTrue(proceed.await(5, TimeUnit.SECONDS));
            return response(inv.getArgument(0), ReservationStatus.CONFIRMED);
        }).when(hotel).confirm(any());
        doAnswer(inv -> response(inv.getArgument(0), ReservationStatus.RELEASED)).when(hotel).cancel(any());
        var executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> service.create(request, "alice"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var saved = bookings.findByRequestId(request.requestId()).orElseThrow();
            assertEquals(BookingStatus.CANCELLED, cancellations.cancel(saved.getId(), "alice").status());
            proceed.countDown();
            assertEquals(BookingStatus.CANCELLED, future.get(10, TimeUnit.SECONDS).status());
            assertEquals(BookingStatus.CANCELLED, queries.get(saved.getId(), "alice").status());
        } finally {
            proceed.countDown(); executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
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
