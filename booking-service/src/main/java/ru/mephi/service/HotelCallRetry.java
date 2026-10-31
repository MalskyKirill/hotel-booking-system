package ru.mephi.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.mephi.exception.RemoteServiceUncertainException;

import java.util.function.Supplier;

@Component
public class HotelCallRetry {
    private static final Logger log = LoggerFactory.getLogger(HotelCallRetry.class);
    private final int attempts;
    private final long initialDelay;
    private final Sleeper sleeper;

    @Autowired
    public HotelCallRetry(@Value("${booking.saga.remote.max-attempts:3}") int attempts,
                          @Value("${booking.saga.remote.initial-delay-ms:100}") long initialDelay) {
        this(attempts, initialDelay, Thread::sleep);
    }

    HotelCallRetry(int attempts, long initialDelay, Sleeper sleeper) {
        if (attempts < 1 || attempts > 10 || initialDelay < 0 || initialDelay > 2000) {
            throw new IllegalArgumentException("Invalid Saga retry limits");
        }
        this.attempts = attempts;
        this.initialDelay = initialDelay;
        this.sleeper = sleeper;
    }

    public <T> T execute(Long bookingId, String operation, Supplier<T> action) {
        for (int attempt = 1; ; attempt++) {
            try {
                return action.get();
            } catch (RemoteServiceUncertainException ex) {
                if (attempt == attempts) {
                    throw ex;
                }
                long delay = Math.min(2000, initialDelay * (1L << (attempt - 1)));
                log.warn("Retry Hotel: bookingId={}, operation={}, attempt={}, delayMs={}",
                    bookingId, operation, attempt, delay);
                try {
                    sleeper.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new RemoteServiceUncertainException("Saga retry interrupted", interrupted);
                }
            }
        }
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long milliseconds) throws InterruptedException;
    }
}
