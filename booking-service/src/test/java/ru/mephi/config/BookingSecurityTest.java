package ru.mephi.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.mephi.controller.BookingController;
import ru.mephi.dto.BookingResponse;
import ru.mephi.dto.CreateBookingRequest;
import ru.mephi.model.BookingStatus;
import ru.mephi.service.BookingService;

import java.time.Instant;
import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BookingController.class)
@Import(SecurityConfig.class)
class BookingSecurityTest {

    private static final String REQUEST = """
        {"roomId":2,"startDate":"2027-01-01","endDate":"2027-01-02","requestId":"security-test"}
        """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private BookingService bookingService;

    @MockitoBean
    private ru.mephi.service.BookingQueryService queries;

    @ParameterizedTest
    @ValueSource(strings = {"USER", "ADMIN"})
    void allowsUserAndAdmin(String role) throws Exception {
        when(jwtDecoder.decode("test-token")).thenReturn(jwt(role));
        when(bookingService.create(any(CreateBookingRequest.class), eq("alice")))
            .thenReturn(new BookingResponse(1L, 2L, LocalDate.of(2027, 1, 1),
                LocalDate.of(2027, 1, 2), BookingStatus.CONFIRMED, Instant.now()));

        mvc.perform(post("/api/bookings")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON).content(REQUEST))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(1));

        verify(bookingService).create(any(CreateBookingRequest.class), eq("alice"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"SERVICE", "UNKNOWN"})
    void forbidsServiceAndMissingOrUnknownRole(String role) throws Exception {
        when(jwtDecoder.decode("test-token")).thenReturn(jwt(role));

        mvc.perform(post("/api/bookings")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON).content(REQUEST))
            .andExpect(status().isForbidden());

        verifyNoInteractions(bookingService);
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(post("/api/bookings")
                .contentType(MediaType.APPLICATION_JSON).content(REQUEST))
            .andExpect(status().isUnauthorized());

        verifyNoInteractions(bookingService);
    }

    @Test
    void rejectsInvalidToken() throws Exception {
        when(jwtDecoder.decode("invalid-token")).thenThrow(new BadJwtException("Invalid token"));

        mvc.perform(post("/api/bookings")
                .header("Authorization", "Bearer invalid-token")
                .contentType(MediaType.APPLICATION_JSON).content(REQUEST))
            .andExpect(status().isUnauthorized());

        verifyNoInteractions(bookingService);
    }

    @ParameterizedTest
    @CsvSource({"PENDING,202", "CANCELLED,409"})
    void exposesSagaOutcomeWithoutClaimingPrematureSuccess(BookingStatus bookingStatus, int httpStatus) throws Exception {
        when(jwtDecoder.decode("test-token")).thenReturn(jwt("USER"));
        when(bookingService.create(any(CreateBookingRequest.class), eq("alice")))
            .thenReturn(new BookingResponse(1L, 2L, LocalDate.of(2027, 1, 1),
                LocalDate.of(2027, 1, 2), bookingStatus, Instant.now()));
        mvc.perform(post("/api/bookings")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON).content(REQUEST))
            .andExpect(status().is(httpStatus))
            .andExpect(jsonPath("$.status").value(bookingStatus.name()))
            .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void automaticRequestIgnoresRoomIdAndAllowsSelectionWithoutIt() throws Exception {
        when(jwtDecoder.decode("test-token")).thenReturn(jwt("USER"));
        when(bookingService.create(any(CreateBookingRequest.class), eq("alice")))
            .thenReturn(new BookingResponse(1L, 7L, LocalDate.of(2027, 1, 1),
                LocalDate.of(2027, 1, 2), BookingStatus.CONFIRMED, Instant.now()));
        mvc.perform(post("/api/bookings").header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"autoSelect":true,"roomId":-1,"hotelId":5,"startDate":"2027-01-01", "endDate":"2027-01-02","requestId":"auto"}
                    """))
            .andExpect(status().isOk()).andExpect(jsonPath("$.roomId").value(7));
        var capture = org.mockito.ArgumentCaptor.forClass(CreateBookingRequest.class);
        verify(bookingService).create(capture.capture(), eq("alice"));
        org.junit.jupiter.api.Assertions.assertNull(capture.getValue().roomId());
        org.junit.jupiter.api.Assertions.assertTrue(capture.getValue().autoSelect());
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "true,\"hotelId\":-1"})
    void rejectsInvalidSelectionInput(String selection) throws Exception {
        when(jwtDecoder.decode("test-token")).thenReturn(jwt("USER"));
        mvc.perform(post("/api/bookings").header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"autoSelect":%s,"startDate":"2027-01-01","endDate":"2027-01-02","requestId":"auto"}
                    """.formatted(selection)))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(bookingService);
    }

    @Test
    void historyUsesJwtSubjectAndDoesNotAcceptAnotherOwner() throws Exception {
        when(jwtDecoder.decode("test-token")).thenReturn(jwt("USER"));
        when(queries.history("alice", 0, 20)).thenReturn(new ru.mephi.dto.BookingPage(java.util.List.of(), 0, 20, 0, 0));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/bookings")
                .param("username", "bob").header("Authorization", "Bearer test-token"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        verify(queries).history("alice", 0, 20);
    }

    @Test
    void missingAndForeignBookingHaveSameHttpStatus() throws Exception {
        when(jwtDecoder.decode("test-token")).thenReturn(jwt("USER"));
        when(queries.get(9L, "alice")).thenThrow(new ru.mephi.exception.BookingNotFoundException());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/bookings/9")
                .header("Authorization", "Bearer test-token"))
            .andExpect(status().isNotFound());
    }

    private Jwt jwt(String role) {
        Jwt.Builder builder = Jwt.withTokenValue("test-token")
            .header("alg", "RS256")
            .subject("alice");
        if (role != null) {
            builder.claim("role", role);
        }
        return builder.build();
    }
}
