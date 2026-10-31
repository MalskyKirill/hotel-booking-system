package ru.mephi.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import ru.mephi.exception.*;
import java.util.stream.Stream;
import java.net.SocketTimeoutException;
import java.net.ConnectException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import ru.mephi.dto.CreateReservationRequest;
import ru.mephi.dto.ReservationResponse;
import ru.mephi.dto.ReservationStatus;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class HotelReservationClientTest {
    private MockRestServiceServer server;
    private HotelReservationClient client;
    private final CreateReservationRequest request = new CreateReservationRequest(1L, 2L,
        LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 3));

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://hotel-service");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new HotelReservationClient(builder.build());
    }

    @ParameterizedTest
    @CsvSource({"reserve,HELD", "reserve,CONFIRMED", "confirm,CONFIRMED", "release,RELEASED",
        "status,HELD", "status,CONFIRMED", "status,RELEASED"})
    void sendsOperationAndReadsExpectedState(String operation, ReservationStatus status) {
        var expectation = server.expect(requestTo(url(operation)))
            .andExpect(method(operation.equals("status") ? HttpMethod.GET : HttpMethod.POST));
        if (operation.equals("reserve") || operation.equals("release")) {
            expectation.andExpect(httpRequest -> {
                String body = ((org.springframework.mock.http.client.MockClientHttpRequest) httpRequest).getBodyAsString();
                CreateReservationRequest sent = new com.fasterxml.jackson.databind.ObjectMapper()
                    .findAndRegisterModules().readValue(body, CreateReservationRequest.class);
                assertEquals(request, sent);
            });
        }
        expectation.andRespond(withSuccess(response(status.name()), MediaType.APPLICATION_JSON));

        ReservationResponse result = invoke(operation);

        assertEquals(new ReservationResponse(1L, 2L, request.startDate(), request.endDate(), status), result);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"RESERVATION_CONFLICT", "IDEMPOTENCY_CONFLICT", "INVALID_RESERVATION_STATE"})
    void preservesBusinessConflictCode(String code) {
        server.expect(requestTo(url("reserve"))).andRespond(withStatus(HttpStatus.CONFLICT)
            .contentType(MediaType.APPLICATION_JSON).body("{\"code\":\"" + code + "\",\"message\":\"conflict\"}"));
        RemoteBusinessConflictException error = assertThrows(RemoteBusinessConflictException.class,
            () -> client.reserve(request));
        assertEquals(code, error.getCode());
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 429})
    void distinguishesRejectedRequestFromUncertainOutcome(int status) {
        server.expect(requestTo(url("reserve"))).andRespond(withStatus(HttpStatus.valueOf(status)));
        RemoteHotelRequestException error = assertThrows(RemoteHotelRequestException.class,
            () -> client.reserve(request));
        assertEquals(status, error.getStatus());
        server.verify();
    }

    @Test
    void reserve404MeansRoomNotFound() {
        server.expect(requestTo(url("reserve"))).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThrows(RemoteRoomNotFoundException.class, () -> client.reserve(request));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"confirm", "release", "status"})
    void operation404MeansReservationNotFound(String operation) {
        server.expect(requestTo(url(operation))).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThrows(RemoteReservationNotFoundException.class, () -> invoke(operation));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 503})
    void serverFailureHasUncertainOutcome(int status) {
        server.expect(requestTo(url("reserve"))).andRespond(withStatus(HttpStatus.valueOf(status)));
        assertThrows(RemoteServiceUncertainException.class, () -> client.reserve(request));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void networkFailureHasUncertainOutcome(boolean timeout) {
        server.expect(requestTo(url("reserve"))).andRespond(withException(timeout
            ? new SocketTimeoutException("Read timed out") : new ConnectException("Connection refused")));
        assertThrows(RemoteServiceUncertainException.class, () -> client.reserve(request));
        server.verify();
    }

    @ParameterizedTest
    @MethodSource("invalidResponses")
    void malformedOrMismatchedResponseHasUncertainOutcome(String body) {
        server.expect(requestTo(url("reserve"))).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertThrows(RemoteServiceUncertainException.class, () -> client.reserve(request));
        server.verify();
    }

    static Stream<String> invalidResponses() {
        String valid = response("HELD");
        return Stream.of("", "{}", "{", response("UNKNOWN"),
            valid.replace("\"bookingId\":1", "\"bookingId\":9"),
            valid.replace("\"roomId\":2", "\"roomId\":9"),
            valid.replace("2027-01-01", "2027-01-02"),
            valid.replace("2027-01-03", "2027-01-04"),
            valid.replace("\"HELD\"", "null"));
    }

    @Test
    void releasedReservationIsTerminalBusinessConflict() {
        server.expect(requestTo(url("reserve"))).andRespond(withSuccess(response("RELEASED"), MediaType.APPLICATION_JSON));
        assertEquals("RESERVATION_RELEASED", assertThrows(RemoteBusinessConflictException.class,
            () -> client.reserve(request)).getCode());
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({"confirm,HELD", "confirm,RELEASED", "release,CONFIRMED", "release,HELD"})
    void unexpectedOperationStateIsNotSuccess(String operation, String status) {
        server.expect(requestTo(url(operation))).andRespond(withSuccess(response(status), MediaType.APPLICATION_JSON));
        assertThrows(RemoteServiceUncertainException.class, () -> invoke(operation));
        server.verify();
    }

    private ReservationResponse invoke(String operation) {
        return switch (operation) {
            case "reserve" -> client.reserve(request);
            case "confirm" -> client.confirm(request);
            case "release" -> client.release(request);
            case "status" -> client.getStatus(request);
            default -> throw new IllegalArgumentException(operation);
        };
    }

    private String url(String operation) {
        return "http://hotel-service/internal/reservations" + switch (operation) {
            case "reserve" -> "";
            case "status" -> "/1";
            default -> "/1/" + operation;
        };
    }

    private static String response(String status) {
        return """
            {"bookingId":1,"roomId":2,"startDate":"2027-01-01","endDate":"2027-01-03","status":"%s"}
            """.formatted(status);
    }
}
