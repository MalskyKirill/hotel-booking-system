package ru.mephi.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import ru.mephi.dto.*;
import ru.mephi.exception.*;
import java.time.LocalDate;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class AutoReservationClientTest {
    MockRestServiceServer server;
    HotelReservationClient client;
    final AutoReservationRequest request = new AutoReservationRequest(1L, 5L,
        LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 3));

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder().baseUrl("http://hotel-service");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new HotelReservationClient(builder.build());
    }
    @ParameterizedTest
    @CsvSource({"reserve,HELD,7", "reserve,CONFIRMED,7", "confirm,CONFIRMED,7",
        "release,RELEASED,null", "cancel,RELEASED,null", "status,RELEASED,null", "status,HELD,7"})
    void usesAutomaticOperationAndAcceptsUnselectedCancellation(String operation, String status, String room) {
        server.expect(requestTo(url(operation))).andExpect(method(HttpMethod.POST))
            .andExpect(http -> {
                var body = ((org.springframework.mock.http.client.MockClientHttpRequest) http).getBodyAsString();
                assertEquals(request, new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                    .readValue(body, AutoReservationRequest.class));
            }).andRespond(withSuccess(response(status, room), MediaType.APPLICATION_JSON));
        assertEquals(ReservationStatus.valueOf(status), invoke(operation).status());
        server.verify();
    }
    @ParameterizedTest
    @MethodSource("invalidResponses")
    void rejectsUntrustedResponse(String body) {
        server.expect(requestTo(url("reserve"))).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertThrows(RemoteServiceUncertainException.class, () -> invoke("reserve"));
        server.verify();
    }
    static Stream<String> invalidResponses() {
        return Stream.of("", "{}", "{", response("UNKNOWN", "7"), response("HELD", "null"),
            response("HELD", "0"), response("RELEASED", "-1"),
            response("HELD", "7").replace("\"bookingId\":1", "\"bookingId\":2"),
            response("HELD", "7").replace("2027-01-03", "2027-01-04"));
    }
    @Test
    void alreadyCancelledSelectionCannotBeReservedAgain() {
        server.expect(requestTo(url("reserve"))).andRespond(withSuccess(response("RELEASED", "null"), MediaType.APPLICATION_JSON));
        assertEquals("RESERVATION_RELEASED", assertThrows(RemoteBusinessConflictException.class, () -> invoke("reserve")).getCode());
    }
    @Test
    void preservesNoAvailableRoomConflict() {
        server.expect(requestTo(url("reserve"))).andRespond(withStatus(HttpStatus.CONFLICT)
            .contentType(MediaType.APPLICATION_JSON).body("{\"code\":\"NO_AVAILABLE_ROOM\"}"));
        assertEquals("NO_AVAILABLE_ROOM", assertThrows(RemoteBusinessConflictException.class, () -> invoke("reserve")).getCode());
    }
    @Test
    void absentSelectionRemainsDistinctFromUnknownResult() {
        server.expect(requestTo(url("status"))).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThrows(RemoteReservationNotFoundException.class, () -> invoke("status"));
    }
    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void authorizationFailureIsNotUncertain(int code) {
        server.expect(requestTo(url("reserve"))).andRespond(withStatus(HttpStatus.valueOf(code)));
        assertThrows(RemoteHotelRequestException.class, () -> invoke("reserve"));
    }
    @Test
    void serverFailureIsUncertain() {
        server.expect(requestTo(url("reserve"))).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThrows(RemoteServiceUncertainException.class, () -> invoke("reserve"));
    }
    ReservationResponse invoke(String operation) {
        return switch (operation) {
            case "reserve" -> client.reserveAuto(request);
            case "confirm" -> client.confirmAuto(request);
            case "release" -> client.releaseAuto(request);
            case "cancel" -> client.cancelAuto(request);
            default -> client.getAutoStatus(request);
        };
    }
    String url(String operation) {
        return "http://hotel-service/internal/auto-reservations" + (operation.equals("reserve") ? "" : "/" + operation);
    }
    static String response(String status, String room) {
        return """
            {"bookingId":1,"roomId":%s,"startDate":"2027-01-01","endDate":"2027-01-03","status":"%s"}
            """.formatted(room, status);
    }
}
