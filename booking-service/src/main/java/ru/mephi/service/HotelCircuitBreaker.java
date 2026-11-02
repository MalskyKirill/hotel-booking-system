package ru.mephi.service;

import io.github.resilience4j.circuitbreaker.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.mephi.exception.RemoteCircuitOpenException;
import ru.mephi.exception.RemoteServiceUncertainException;
import java.time.Duration;
import java.util.function.Supplier;

/** One circuit for Hotel, outside retry: only exhausted uncertain calls count as failures. */
@Component
public class HotelCircuitBreaker {
    private static final Logger log = LoggerFactory.getLogger(HotelCircuitBreaker.class);
    private final CircuitBreaker circuit;
    private final boolean enabled;

    public HotelCircuitBreaker(@Value("${booking.saga.circuit.enabled:true}") boolean enabled,
        @Value("${booking.saga.circuit.sliding-window-size:10}") int window,
        @Value("${booking.saga.circuit.minimum-number-of-calls:5}") int minimumCalls,
        @Value("${booking.saga.circuit.failure-rate-threshold:50}") float failureRate,
        @Value("${booking.saga.circuit.wait-duration-ms:10000}") long waitMs,
        @Value("${booking.saga.circuit.half-open-calls:2}") int halfOpenCalls) {
        if (minimumCalls > window) throw new IllegalArgumentException("Circuit minimum calls must not exceed its window");
        this.enabled = enabled;
        circuit = CircuitBreaker.of("hotel-service", CircuitBreakerConfig.custom()
            .slidingWindowSize(window).minimumNumberOfCalls(minimumCalls).failureRateThreshold(failureRate)
            .waitDurationInOpenState(Duration.ofMillis(waitMs)).permittedNumberOfCallsInHalfOpenState(halfOpenCalls)
            .recordException(ex -> ex instanceof RemoteServiceUncertainException)
            .ignoreException(ex -> !(ex instanceof RemoteServiceUncertainException)).build());
        circuit.getEventPublisher().onStateTransition(event -> log.atWarn()
            .addKeyValue("circuit.name", circuit.getName())
            .addKeyValue("circuit.from", event.getStateTransition().getFromState().name())
            .addKeyValue("circuit.to", event.getStateTransition().getToState().name())
            .log("Hotel circuit state changed"));
    }

    public <T> T execute(Long bookingId, String operation, Supplier<T> action) {
        if (!enabled) return action.get();
        try {
            return circuit.executeSupplier(action);
        } catch (CallNotPermittedException ex) {
            log.warn("Hotel call blocked: bookingId={}, operation={}", bookingId, operation);
            throw new RemoteCircuitOpenException(ex);
        }
    }

    CircuitBreaker circuit() { return circuit; }
}
