package ru.mephi.observability;

import org.junit.jupiter.api.*;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static ru.mephi.observability.TraceContext.*;

class TracePropagationInterceptorTest {
    @AfterEach void clean() { MDC.clear(); }
    @Test void forwardsMdcAndReplacesConflictingOutgoingHeader() {
        var builder = RestClient.builder().requestInterceptor(new TracePropagationInterceptor());
        var server = MockRestServiceServer.bindTo(builder).build();
        MDC.put(MDC_KEY, "incoming-trace");
        server.expect(requestTo("http://hotel/internal/reservations"))
            .andExpect(header(HEADER, "incoming-trace")).andRespond(withSuccess());
        builder.build().get().uri("http://hotel/internal/reservations").header(HEADER, "wrong")
            .retrieve().toBodilessEntity();
        server.verify(); assertEquals("incoming-trace", MDC.get(MDC_KEY));
    }
    @Test void generatesAValidContextForAnUntracedCallAndClearsItAfterward() {
        var builder = RestClient.builder().requestInterceptor(new TracePropagationInterceptor());
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://hotel/status")).andExpect(request -> {
            String id = request.getHeaders().getFirst(HEADER);
            assertTrue(valid(id)); assertEquals(id, MDC.get(MDC_KEY));
        }).andRespond(withSuccess());
        builder.build().get().uri("http://hotel/status").retrieve().toBodilessEntity();
        server.verify(); assertNull(MDC.get(MDC_KEY));
    }
    @Test void failureDoesNotLeakGeneratedContext() {
        var builder = RestClient.builder().requestInterceptor(new TracePropagationInterceptor());
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://hotel/status")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThrows(RestClientResponseException.class, () -> builder.build().get().uri("http://hotel/status").retrieve().toBodilessEntity());
        server.verify(); assertNull(MDC.get(MDC_KEY));
    }
}
