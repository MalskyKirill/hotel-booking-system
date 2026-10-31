package ru.mephi.client;

import org.junit.jupiter.api.BeforeEach;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

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

    private String response(String status) {
        return """
            {"bookingId":1,"roomId":2,"startDate":"2027-01-01","endDate":"2027-01-03","status":"%s"}
            """.formatted(status);
    }
}
