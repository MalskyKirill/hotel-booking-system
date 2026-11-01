package ru.mephi.system;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import java.util.concurrent.*;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
import static ru.mephi.system.HotelFaultProxy.Action.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(90)
class BookingSystemIT {
    private LiveStack s;
    @BeforeAll void start() throws Exception {
        s = new LiveStack();
        try { s.start(); } catch (Exception | Error ex) { s.close(); throw ex; }
    }
    @AfterAll void stop() { if (s != null) s.close(); }
    @BeforeEach @Timeout(70) void isolateCircuitState() throws Exception { s.establishHealthyCircuit(); }

    @Test void accessAndErrorContract() throws Exception {
        s.get("/api/hotels", null).error(401, "UNAUTHORIZED", "/api/hotels");
        s.get("/api/admin/users", s.user).error(403, "ACCESS_DENIED", "/api/admin/users");
        s.get("/api/hotels", s.serviceToken).error(403, "ACCESS_DENIED", "/api/hotels");
        s.get("/api/hotels", "invalid").error(401, "UNAUTHORIZED", "/api/hotels");
        s.get("/api/hotels", s.user).require(200);
        s.get("/internal/reservations/1", s.serviceToken).error(404, "NOT_FOUND", "/internal/reservations/1");
        s.call("hotel", "/internal/reservations/1", "GET", null, s.user, 10).error(401, "UNAUTHORIZED", "/internal/reservations/1");
        s.get("/api/rooms", s.user).error(400, "MISSING_PARAMETER", "/api/rooms");
        for (String service : List.of("hotel", "booking"))
            s.call(service, "/h2-console/", "GET", null, s.admin, 10).error(404, "NOT_FOUND", "/h2-console/");
    }

    @Test void gatewayServesSwaggerUiAndBothOpenApiDocuments() throws Exception {
        for (String service : List.of("booking", "hotel")) {
            JsonNode spec = s.get("/v3/api-docs/" + service, null).require(200);
            assertTrue(spec.path("openapi").asText().startsWith("3."));
            assertEquals("bearer", spec.path("components").path("securitySchemes").path("bearerAuth").path("scheme").asText());
            assertEquals("/", spec.path("servers").get(0).path("url").asText());
            spec.path("paths").fieldNames().forEachRemaining(path -> assertTrue(path.startsWith("/api/")));
        }
        JsonNode config = s.get("/v3/api-docs/swagger-config", null).require(200);
        assertEquals(2, config.path("urls").size());
        assertTrue(s.get("/swagger-ui/index.html", null).require(200).asText().contains("swagger-ui"));
        s.get("/api/bookings", null).error(401, "UNAUTHORIZED", "/api/bookings");
    }

    @Test void lifecycleOwnershipAndIdempotency() throws Exception {
        var fixture = s.fixture(1);
        long room = fixture.rooms().getFirst();
        var request = s.booking(fixture, false);
        JsonNode confirmed = s.create(request).require(200);
        long id = confirmed.path("id").asLong();
        assertEquals("CONFIRMED", confirmed.path("status").asText());
        assertEquals("CONFIRMED", s.reservation(id).path("status").asText());
        assertEquals(confirmed, s.create(request).require(200));
        assertEquals(1, s.room(room).path("timesBooked").asInt());
        var changed = new HashMap<>(request);
        changed.put("endDate", "2027-01-04");
        s.create(changed).error(409, "IDEMPOTENCY_CONFLICT", "/api/bookings");
        s.call("/api/bookings", "POST", request, s.other).error(409, "IDEMPOTENCY_CONFLICT", "/api/bookings");
        s.get("/api/bookings/" + id, s.other).error(404, "BOOKING_NOT_FOUND", "/api/bookings/" + id);
        s.call("/api/bookings/" + id, "DELETE", null, s.other).error(404, "BOOKING_NOT_FOUND", "/api/bookings/" + id);
        assertEquals(0, s.get("/api/bookings", s.other).require(200).path("totalElements").asInt());
        assertTrue(s.search(fixture.hotel(), false).isEmpty());
        assertEquals(room, s.search(fixture.hotel(), true).get(0).path("id").asLong());
        long rejected = s.create(s.booking(fixture, false)).error(409, "BOOKING_CANCELLED", "/api/bookings")
            .path("fields").path("bookingId").asLong();
        assertEquals("CANCELLED", s.booking(rejected).path("status").asText());
        assertEquals("RELEASED", s.reservation(rejected).path("status").asText());
        assertEquals("CONFIRMED", s.reservation(id).path("status").asText());
        JsonNode cancelled = s.call("/api/bookings/" + id, "DELETE", null, s.user).require(200);
        assertEquals("CANCELLED", cancelled.path("status").asText());
        assertEquals(cancelled, s.call("/api/bookings/" + id, "DELETE", null, s.user).require(200));
        assertEquals("RELEASED", s.reservation(id).path("status").asText());
        assertEquals(room, s.search(fixture.hotel(), false).get(0).path("id").asLong());
        assertEquals(1, s.room(room).path("timesBooked").asInt());
    }

    private List<LiveStack.Response> burst(List<Map<String, Object>> requests) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(8);
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<Future<LiveStack.Response>> results = new ArrayList<>();
            for (var request : requests) results.add(executor.submit(() -> {
                String trace = "parallel-" + UUID.randomUUID();
                barrier.await(10, TimeUnit.SECONDS);
                var response = s.correlated("/api/bookings", "POST", request, s.user, trace);
                assertEquals(trace, response.traceId());
                for (var call : s.proxy.observations(trace)) assertEquals(trace, call.upstreamTraceId());
                return response;
            }));
            List<LiveStack.Response> responses = new ArrayList<>();
            for (var result : results) responses.add(result.get(60, TimeUnit.SECONDS));
            return responses;
        }
    }
    private JsonNode outcome(LiveStack.Response response) throws Exception {
        if (response.status() == 409) {
            long id = response.error(409, "BOOKING_CANCELLED", "/api/bookings").path("fields").path("bookingId").asLong();
            return s.settled(id, "CANCELLED");
        }
        assertTrue(response.status() == 200 || response.status() == 202, "Unexpected concurrent HTTP status");
        return s.settled(response.body().path("id").asLong(), null);
    }
    @Test void concurrentRepeatedKeyCreatesOneBooking() throws Exception {
        var fixture = s.fixture(1);
        var request = s.booking(fixture, false);
        Set<Long> ids = new HashSet<>();
        for (var response : burst(Collections.nCopies(8, request))) {
            JsonNode booking = outcome(response);
            ids.add(booking.path("id").asLong());
            assertEquals("CONFIRMED", booking.path("status").asText());
        }
        assertEquals(1, ids.size());
        assertEquals("CONFIRMED", s.reservation(ids.iterator().next()).path("status").asText());
        assertEquals(1, s.room(fixture.rooms().getFirst()).path("timesBooked").asInt());
    }
    @Test void concurrentRoomCollisionConfirmsExactlyOne() throws Exception {
        var fixture = s.fixture(1);
        List<Map<String, Object>> requests = new ArrayList<>();
        for (int i = 0; i < 8; i++) requests.add(s.booking(fixture, false));
        int confirmed = 0;
        Set<Long> ids = new HashSet<>();
        for (var response : burst(requests)) {
            JsonNode booking = outcome(response);
            long id = booking.path("id").asLong();
            ids.add(id);
            boolean success = booking.path("status").asText().equals("CONFIRMED");
            if (success) confirmed++;
            assertEquals(success ? "CONFIRMED" : "RELEASED", s.reservation(id).path("status").asText());
        }
        assertEquals(8, ids.size());
        assertEquals(1, confirmed);
        JsonNode stat = statistics(fixture.hotel()).get(0);
        assertEquals(1, stat.path("confirmedReservations").asInt());
        assertEquals(0, stat.path("heldReservations").asInt());
        assertEquals(2, stat.path("confirmedNights").asInt());
        assertEquals(100, stat.path("confirmedOccupancyPercent").asDouble());
        assertEquals(1, s.room(fixture.rooms().getFirst()).path("timesBooked").asInt());
    }
    private JsonNode statistics(long hotel) throws Exception {
        return s.get("/api/admin/rooms/statistics?hotelId=" + hotel + "&startDate=2027-01-01&endDate=2027-01-03", s.admin)
            .require(200).path("content");
    }
    @Test void concurrentAutoSelectionUsesThreeDistinctAvailableRooms() throws Exception {
        var fixture = s.fixture(4);
        s.call("/api/admin/rooms/" + fixture.rooms().getLast(), "PUT", Map.of("roomNumber", "4", "capacity", 2, "price", 100,
            "available", false), s.admin).require(200);
        List<Map<String, Object>> requests = new ArrayList<>();
        for (int i = 0; i < 8; i++) requests.add(s.booking(fixture, true));
        List<LiveStack.Response> responses = burst(requests);
        Set<Long> selected = new HashSet<>();
        int confirmed = 0;
        for (int i = 0; i < responses.size(); i++) {
            JsonNode booking = outcome(responses.get(i));
            boolean success = booking.path("status").asText().equals("CONFIRMED");
            if (success) { confirmed++; selected.add(booking.path("roomId").asLong()); }
            assertEquals(success ? "CONFIRMED" : "RELEASED", s.remote(booking.path("id").asLong(), requests.get(i)).path("status").asText());
        }
        assertEquals(3, confirmed);
        assertEquals(new HashSet<>(fixture.rooms().subList(0, 3)), selected);
        assertTrue(s.search(fixture.hotel(), false).isEmpty());
        JsonNode adjacent = s.search(fixture.hotel(), true);
        assertEquals(3, adjacent.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(fixture.rooms().get(i).longValue(), adjacent.get(i).path("id").asLong());
            assertEquals(1, adjacent.get(i).path("timesBooked").asInt());
        }
        int active = 0;
        for (JsonNode stat : statistics(fixture.hotel())) {
            assertEquals(0, stat.path("heldReservations").asInt());
            active += stat.path("confirmedReservations").asInt();
        }
        assertEquals(3, active);
    }

    @Test void lostReserveResponseCompensatesHeldReservation() throws Exception {
        var fixture = s.fixture(1);
        var request = s.booking(fixture, false);
        try (var fault = s.proxy.rule((m, p) -> m.equals("POST") && p.equals("/internal/reservations"), DROP_AFTER)) {
            long id = s.create(request).error(409, "BOOKING_CANCELLED", "/api/bookings").path("fields").path("bookingId").asLong();
            assertEquals("CANCELLED", s.booking(id).path("status").asText());
            assertEquals("RELEASED", s.reservation(id).path("status").asText());
            assertEquals(0, s.room(fixture.rooms().getFirst()).path("timesBooked").asInt());
            fault.requireCommitted();
        }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void lostConfirmResponsesPreserveCommittedReservation(boolean automatic) throws Exception {
        var fixture = s.fixture(1);
        var request = s.booking(fixture, automatic);
        try (var fault = s.proxy.rule((m, p) -> m.equals("POST") && p.startsWith("/internal/") && p.endsWith("/confirm"), DROP_AFTER)) {
            JsonNode booking = s.create(request).require(200);
            assertEquals("CONFIRMED", booking.path("status").asText());
            assertEquals("CONFIRMED", s.remote(booking.path("id").asLong(), request).path("status").asText());
            assertEquals(1, s.room(fixture.rooms().getFirst()).path("timesBooked").asInt());
            fault.requireCommitted();
        }
    }
    @Test void realReadTimeoutAndLateReserveCannotRecreateHeld() throws Exception {
        var fixture = s.fixture(1);
        try (var fault = s.proxy.rule((m, p) -> m.equals("POST") && p.equals("/internal/reservations"), HOLD_BEFORE)) {
            long id = s.create(s.booking(fixture, false)).error(409, "BOOKING_CANCELLED", "/api/bookings")
                .path("fields").path("bookingId").asLong();
            assertEquals("RELEASED", s.reservation(id).path("status").asText());
            fault.release();
            fault.requireCommitted();
            assertEquals("RELEASED", s.reservation(id).path("status").asText());
            assertEquals(0, s.room(fixture.rooms().getFirst()).path("timesBooked").asInt());
        }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void schedulerCompensatesUnknownResultWithoutAnotherPost(boolean automatic) throws Exception {
        var fixture = s.fixture(1);
        var request = s.booking(fixture, automatic);
        long id;
        String originalTrace;
        try (var fault = s.proxy.rule((m, p) -> p.startsWith("/internal/"), REJECT_BEFORE)) {
            var pendingResponse = s.create(request);
            originalTrace = pendingResponse.traceId();
            JsonNode pending = pendingResponse.require(202);
            id = pending.path("id").asLong();
            assertEquals("PENDING", pending.path("status").asText());
            if (automatic) assertTrue(pending.path("roomId").isNull());
            assertEquals("PENDING", s.booking(id).path("status").asText());
            assertTrue(fault.hits() >= 3);
        }
        s.settled(id, "CANCELLED"); // GET cannot drive Saga; only the scheduler can settle it.
        s.assertRecoveryTrace(id, originalTrace);
        assertEquals("RELEASED", s.remote(id, request).path("status").asText());
        assertEquals(id, s.create(request).error(409, "BOOKING_CANCELLED", "/api/bookings").path("fields").path("bookingId").asLong());
        assertEquals(0, s.room(fixture.rooms().getFirst()).path("timesBooked").asInt());
    }
    @Test void schedulerCompletesCancellationAfterTransportRecovers() throws Exception {
        var fixture = s.fixture(1);
        long id = s.create(s.booking(fixture, true)).require(200).path("id").asLong();
        try (var fault = s.proxy.rule((m, p) -> p.startsWith("/internal/"), REJECT_BEFORE)) {
            JsonNode pending = s.call("/api/bookings/" + id, "DELETE", null, s.user).require(202);
            assertEquals("CONFIRMED", pending.path("status").asText());
            assertTrue(pending.path("cancellationRequested").asBoolean());
            assertEquals("CONFIRMED", s.reservation(id).path("status").asText());
        }
        s.settled(id, "CANCELLED");
        assertEquals("RELEASED", s.reservation(id).path("status").asText());
        assertEquals(fixture.rooms().getFirst().longValue(), s.search(fixture.hotel(), false).get(0).path("id").asLong());
        assertEquals(1, s.room(fixture.rooms().getFirst()).path("timesBooked").asInt());
    }
    @Test void oneTraceConnectsGatewayBookingHotelAndStructuredLogs() throws Exception {
        var fixture = s.fixture(1);
        String trace = "booking-" + UUID.randomUUID();
        var response = s.correlated("/api/bookings", "POST", s.booking(fixture, false), s.user, trace);
        long id = response.require(200).path("id").asLong();
        assertEquals(trace, response.traceId());
        var calls = s.proxy.observations(trace);
        assertEquals(2, calls.size());
        for (var call : calls) {
            assertEquals(trace, call.upstreamTraceId());
            assertEquals(200, call.upstreamStatus());
        }
        for (String service : List.of("api-gateway", "booking-service", "hotel-service")) s.assertHttpTrace(service, trace, 200);
        s.awaitEvent("booking-service", e -> e.path("traceId").asText().equals(trace)
            && e.path("message").asText().equals("Saga settled: bookingId=" + id + ", status=CONFIRMED"));
    }

    @Test void correlationSurvivesAuthorizationAndValidationErrors() throws Exception {
        String denied = "denied-" + UUID.randomUUID();
        var unauthorized = s.correlated("/api/hotels", "GET", null, null, denied);
        unauthorized.error(401, "UNAUTHORIZED", "/api/hotels");
        assertEquals(denied, unauthorized.traceId());
        s.assertHttpTrace("api-gateway", denied, 401);
        s.assertHttpTrace("hotel-service", denied, 401);
        String invalid = "validation-" + UUID.randomUUID();
        var request = s.booking(s.fixture(1), false);
        request.put("endDate", "2026-01-01");
        var bad = s.correlated("/api/bookings", "POST", request, s.user, invalid);
        bad.error(400, "VALIDATION_FAILED", "/api/bookings");
        assertEquals(invalid, bad.traceId());
        s.assertHttpTrace("api-gateway", invalid, 400);
        s.assertHttpTrace("booking-service", invalid, 400);
        assertTrue(s.proxy.observations(invalid).isEmpty());
    }

    @Test void openCircuitBlocksHttpCallsAndSchedulerRecoversPendingAndCancellation() throws Exception {
        var fixture = s.fixture(1);
        long confirmed = s.create(s.booking(fixture, false)).require(200).path("id").asLong();
        String trace = "circuit-" + UUID.randomUUID();
        Instant started = Instant.now();
        List<Long> pendingIds = new ArrayList<>();
        try (var fault = s.proxy.rule((m, p) -> p.startsWith("/internal/"), REJECT_BEFORE)) {
            // A warmed window has ten successes; five exhausted logical calls open it.
            for (int i = 0; i < 3; i++) {
                var response = s.correlated("/api/bookings", "POST", s.booking(fixture, false), s.user, trace + "-" + i);
                JsonNode pending = response.require(202);
                assertEquals("PENDING", pending.path("status").asText());
                pendingIds.add(pending.path("id").asLong());
            }
            s.awaitEvent("booking-service", e -> e.path("traceId").asText().startsWith(trace)
                && e.path("circuit").path("to").asText().equals("OPEN"));
            int callsBefore = fault.hits();
            var blocked = s.correlated("/api/bookings", "POST", s.booking(fixture, false), s.user, trace + "-blocked");
            pendingIds.add(blocked.require(202).path("id").asLong());
            assertEquals(callsBefore, fault.hits(), "Open circuit still sent HTTP calls");
            JsonNode cancelling = s.call("/api/bookings/" + confirmed, "DELETE", null, s.user).require(202);
            assertTrue(cancelling.path("cancellationRequested").asBoolean());
            assertEquals("CONFIRMED", cancelling.path("status").asText());
            assertEquals(callsBefore, fault.hits(), "Open circuit still attempted cancel/status");
            assertEquals("CONFIRMED", s.reservation(confirmed).path("status").asText());
        }
        // Reads alone observe scheduler recovery. The circuit remains enabled with production timings.
        for (long id : pendingIds) {
            s.settled(id, "CANCELLED");
            assertEquals("RELEASED", s.reservation(id).path("status").asText());
        }
        s.settled(confirmed, "CANCELLED");
        assertEquals("RELEASED", s.reservation(confirmed).path("status").asText());
        s.awaitEvent("booking-service", e -> e.path("circuit").path("to").asText().equals("HALF_OPEN")
            && !Instant.parse(e.path("@timestamp").asText()).isBefore(started));
        s.awaitEvent("booking-service", e -> e.path("circuit").path("from").asText().equals("HALF_OPEN")
            && e.path("circuit").path("to").asText().equals("CLOSED") && !Instant.parse(e.path("@timestamp").asText()).isBefore(started));
        long after = s.create(s.booking(fixture, false)).require(200).path("id").asLong();
        assertEquals("CONFIRMED", s.reservation(after).path("status").asText());
    }

    @Test void lostCancelResponsesReconcileReleasedState() throws Exception {
        var fixture = s.fixture(1);
        long id = s.create(s.booking(fixture, false)).require(200).path("id").asLong();
        try (var fault = s.proxy.rule((m, p) -> m.equals("POST") && p.endsWith("/cancel"), DROP_AFTER)) {
            assertEquals("CANCELLED", s.call("/api/bookings/" + id, "DELETE", null, s.user).require(200).path("status").asText());
            assertEquals("RELEASED", s.reservation(id).path("status").asText());
            fault.requireCommitted();
        }
    }
}
