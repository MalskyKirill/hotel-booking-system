package ru.mephi.service;

import io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import org.junit.jupiter.api.Test;
import ru.mephi.exception.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class HotelCircuitBreakerTest {
    private HotelCircuitBreaker circuit() { return new HotelCircuitBreaker(true, 4, 2, 100, 10000, 2); }
    private void open(HotelCircuitBreaker circuit) {
        for (int i = 0; i < 2; i++) assertThrows(RemoteServiceUncertainException.class,
            () -> circuit.execute(1L, "reserve", () -> { throw new RemoteServiceUncertainException("timeout"); }));
        assertEquals(State.OPEN, circuit.circuit().getState());
    }
    @Test void countsExhaustedRetrySequencesAndDoesNotRetryAnOpenCircuit() {
        var circuit = circuit();
        var retry = new HotelCallRetry(3, 0, delay -> assertEquals(0, delay), circuit);
        var calls = new AtomicInteger();
        for (int i = 0; i < 2; i++) assertThrows(RemoteServiceUncertainException.class, () -> retry.execute(1L, "reserve", () -> {
            calls.incrementAndGet(); throw new RemoteServiceUncertainException("timeout");
        }));
        assertEquals(6, calls.get());
        assertEquals(2, circuit.circuit().getMetrics().getNumberOfFailedCalls());
        assertThrows(RemoteCircuitOpenException.class, () -> retry.execute(2L, "reserve", () -> { calls.incrementAndGet(); return "unexpected"; }));
        assertEquals(6, calls.get());
    }
    @Test void businessNotFoundAndAuthorizationErrorsDoNotEnterFailureStatistics() {
        var circuit = circuit();
        List<RuntimeException> errors = List.of(new RemoteBusinessConflictException("occupied"),
            new RemoteHotelRequestException(403, "ACCESS_DENIED"), new RemoteRoomNotFoundException(1L),
            new RemoteReservationNotFoundException(1L));
        for (int i = 0; i < 4; i++) for (var error : errors)
            assertThrows(error.getClass(), () -> circuit.execute(1L, "status", () -> { throw error; }));
        assertEquals(State.CLOSED, circuit.circuit().getState());
        assertEquals(0, circuit.circuit().getMetrics().getNumberOfBufferedCalls());
    }
    @Test void successfulHalfOpenProbesCloseTheCircuit() {
        var circuit = circuit(); open(circuit);
        circuit.circuit().transitionToHalfOpenState();
        assertEquals("ok", circuit.execute(1L, "status", () -> "ok"));
        assertEquals(State.HALF_OPEN, circuit.circuit().getState());
        assertEquals("ok", circuit.execute(1L, "release", () -> "ok"));
        assertEquals(State.CLOSED, circuit.circuit().getState());
    }
    @Test void failedHalfOpenProbesReopenTheCircuit() {
        var circuit = circuit(); open(circuit);
        circuit.circuit().transitionToHalfOpenState();
        for (int i = 0; i < 2; i++) assertThrows(RemoteServiceUncertainException.class,
            () -> circuit.execute(1L, "status", () -> { throw new RemoteServiceUncertainException("still unavailable"); }));
        assertEquals(State.OPEN, circuit.circuit().getState());
    }
    @Test void concurrentHalfOpenAllowsOnlyConfiguredNumberOfProbes() throws Exception {
        var circuit = circuit(); open(circuit); circuit.circuit().transitionToHalfOpenState();
        var start = new CyclicBarrier(4);
        var entered = new CountDownLatch(2);
        var rejected = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var allowed = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<Future<?>> futures = new ArrayList<>();
            try {
                for (int i = 0; i < 4; i++) futures.add(executor.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    try {
                        circuit.execute(1L, "status", () -> {
                            allowed.incrementAndGet(); entered.countDown();
                            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
                            return "ok";
                        });
                    } catch (RemoteCircuitOpenException ex) { rejected.countDown(); }
                    return null;
                }));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertTrue(rejected.await(5, TimeUnit.SECONDS));
            } finally { release.countDown(); }
            for (var future : futures) future.get(5, TimeUnit.SECONDS);
        }
        assertEquals(2, allowed.get());
        assertEquals(State.CLOSED, circuit.circuit().getState());
    }
    @Test void explicitlyDisabledCircuitLeavesRetryBehaviorIntact() {
        var circuit = new HotelCircuitBreaker(false, 4, 2, 100, 10000, 2);
        var calls = new AtomicInteger();
        for (int i = 0; i < 6; i++) assertThrows(RemoteServiceUncertainException.class,
            () -> circuit.execute(1L, "reserve", () -> { calls.incrementAndGet(); throw new RemoteServiceUncertainException("timeout"); }));
        assertEquals(6, calls.get()); assertEquals(State.CLOSED, circuit.circuit().getState());
    }
}
