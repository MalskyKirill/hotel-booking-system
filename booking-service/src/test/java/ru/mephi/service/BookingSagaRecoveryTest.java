package ru.mephi.service;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BookingSagaRecoveryTest {
    @Test
    void oneBrokenSagaDoesNotBlockOtherBookings() {
        var state = mock(BookingSagaTransactions.class);
        var saga = mock(BookingSagaCoordinator.class);
        when(state.due(any(), eq(2))).thenReturn(List.of(1L, 2L));
        when(saga.resume(1L)).thenThrow(new IllegalStateException("broken item"));

        new BookingSagaRecovery(state, saga, 2).recoverDue();

        verify(state).defer(1L);
        verify(saga).resume(2L);
    }
}
