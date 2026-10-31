package ru.mephi.exception.handler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.mephi.controller.RoomReservationController;
import ru.mephi.dto.CreateReservationRequest;
import ru.mephi.exception.IdempotencyConflictException;
import ru.mephi.exception.InvalidReservationStateException;
import ru.mephi.exception.ReservationConflictException;
import ru.mephi.exception.ReservationNotFoundException;
import ru.mephi.exception.RoomNotFoundException;
import ru.mephi.exception.RoomUnavailableException;
import ru.mephi.service.RoomReservationService;

import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReservationExceptionHandlerTest {

    private RoomReservationService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(RoomReservationService.class);
        mvc = MockMvcBuilders.standaloneSetup(new RoomReservationController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @ParameterizedTest
    @MethodSource("reservationErrors")
    void returnsBusinessStatusAndCode(RuntimeException exception, int expectedStatus, String expectedCode)
        throws Exception {
        when(service.reserve(any(CreateReservationRequest.class))).thenThrow(exception);

        mvc.perform(post("/internal/reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"bookingId":1,"roomId":2,"startDate":"2027-01-01","endDate":"2027-01-02"}
                    """))
            .andExpect(status().is(expectedStatus))
            .andExpect(jsonPath("$.status").value(expectedStatus))
            .andExpect(jsonPath("$.code").value(expectedCode))
            .andExpect(jsonPath("$.message").value(exception.getMessage()))
            .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    static Stream<Arguments> reservationErrors() {
        return Stream.of(
            Arguments.of(new RoomNotFoundException(2L), 404, "ROOM_NOT_FOUND"),
            Arguments.of(new ReservationNotFoundException(1L), 404, "RESERVATION_NOT_FOUND"),
            Arguments.of(new ReservationConflictException(2L, "Dates overlap"), 409, "RESERVATION_CONFLICT"),
            Arguments.of(new RoomUnavailableException(2L), 409, "ROOM_UNAVAILABLE"),
            Arguments.of(new IdempotencyConflictException(1L), 409, "IDEMPOTENCY_CONFLICT"),
            Arguments.of(new InvalidReservationStateException(1L, "Already confirmed"),
                409, "INVALID_RESERVATION_STATE")
        );
    }
}
