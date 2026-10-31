package ru.mephi.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;

@Component
public class BookingSagaRecovery {
    private static final Logger log = LoggerFactory.getLogger(BookingSagaRecovery.class);
    private final BookingSagaTransactions state;
    private final BookingSagaCoordinator saga;
    private final int batchSize;

    public BookingSagaRecovery(BookingSagaTransactions state, BookingSagaCoordinator saga,
                               @Value("${booking.saga.recovery.batch-size:20}") int batchSize) {
        if (batchSize < 1 || batchSize > 100) throw new IllegalArgumentException("Invalid Saga recovery batch size");
        this.state = state;
        this.saga = saga;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${booking.saga.recovery.interval-ms:5000}",
        initialDelayString = "${booking.saga.recovery.initial-delay-ms:30000}")
    public void recoverDue() {
        for (Long id : state.due(Instant.now(), batchSize)) {
            try {
                saga.resume(id);
            } catch (RuntimeException ex) {
                log.error("Saga recovery failed: bookingId={}, cause={}", id, ex.getClass().getSimpleName());
                try {
                    state.defer(id);
                } catch (RuntimeException deferFailure) {
                    log.error("Cannot defer Saga: bookingId={}, cause={}", id, deferFailure.getClass().getSimpleName());
                }
            }
        }
    }
}
