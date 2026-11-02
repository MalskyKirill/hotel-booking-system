package ru.mephi.service;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.ArrayList;
import org.slf4j.MDC;
import org.junit.jupiter.api.AfterEach;
import ru.mephi.observability.TraceContext;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BookingSagaRecoveryTest {
    @AfterEach void cleanContext() { MDC.clear(); }

    @Test
    void eachRecoveryHasAnIndependentContextIncludingItsFailureHandling() {
        var state = mock(BookingSagaTransactions.class);
        var saga = mock(BookingSagaCoordinator.class);
        var traces = new ArrayList<String>();
        MDC.put(TraceContext.MDC_KEY, "outer-context");
        when(state.due(any(), eq(2))).thenReturn(List.of(1L, 2L));
        when(saga.resume(1L)).thenAnswer(invocation -> {
            traces.add(MDC.get(TraceContext.MDC_KEY));
            throw new IllegalStateException("recoverable");
        });
        doAnswer(invocation -> {
            assertEquals(traces.getFirst(), MDC.get(TraceContext.MDC_KEY));
            return null;
        }).when(state).defer(1L);
        when(saga.resume(2L)).thenAnswer(invocation -> {
            traces.add(MDC.get(TraceContext.MDC_KEY));
            return null;
        });
        new BookingSagaRecovery(state, saga, 2).recoverDue();
        assertEquals(2, traces.size());
        assertTrue(traces.stream().allMatch(TraceContext::valid));
        assertNotEquals(traces.get(0), traces.get(1));
        assertEquals("outer-context", MDC.get(TraceContext.MDC_KEY));
        verify(state).defer(1L);
    }
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
