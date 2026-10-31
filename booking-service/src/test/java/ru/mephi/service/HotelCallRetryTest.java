package ru.mephi.service;

import org.junit.jupiter.api.Test;
import ru.mephi.exception.RemoteBusinessConflictException;
import ru.mephi.exception.RemoteHotelRequestException;
import ru.mephi.exception.RemoteServiceUncertainException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HotelCallRetryTest {
    @Test
    void retriesUncertainOutcomeWithIncreasingDelay() {
        List<Long> delays = new ArrayList<>();
        var retry = new HotelCallRetry(3, 100, delays::add);
        AtomicInteger calls = new AtomicInteger();
        assertEquals("ok", retry.execute(1L, "reserve", () -> {
            if (calls.incrementAndGet() < 3) throw new RemoteServiceUncertainException("timeout");
            return "ok";
        }));
        assertEquals(3, calls.get());
        assertEquals(List.of(100L, 200L), delays);
    }

    @Test
    void stopsAfterConfiguredAttempts() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(RemoteServiceUncertainException.class, () -> new HotelCallRetry(3, 0).execute(1L, "confirm", () -> {
            calls.incrementAndGet();
            throw new RemoteServiceUncertainException("5xx");
        }));
        assertEquals(3, calls.get());
    }

    @Test
    void doesNotRetryBusinessOrAuthorizationFailure() {
        var retry = new HotelCallRetry(3, 0, delay -> fail("Must not retry"));
        assertThrows(RemoteBusinessConflictException.class, () -> retry.execute(1L, "reserve", () -> {
            throw new RemoteBusinessConflictException("occupied");
        }));
        assertThrows(RemoteHotelRequestException.class, () -> retry.execute(1L, "reserve", () -> {
            throw new RemoteHotelRequestException(403, "HTTP_403");
        }));
    }

    @Test
    void preservesInterruptionAndStopsRetrying() {
        var retry = new HotelCallRetry(3, 100, delay -> { throw new InterruptedException(); });
        try {
            assertThrows(RemoteServiceUncertainException.class, () -> retry.execute(1L, "reserve", () -> {
                throw new RemoteServiceUncertainException("timeout");
            }));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
