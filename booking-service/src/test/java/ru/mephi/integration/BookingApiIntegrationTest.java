package ru.mephi.integration;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.mephi.client.HotelReservationClient;
import ru.mephi.dto.*;
import ru.mephi.exception.*;
import ru.mephi.repository.*;
import ru.mephi.service.JwtService;
import java.security.*;
import java.security.interfaces.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"eureka.client.enabled=false", "booking.saga.recovery.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:booking-api-tests", "spring.jpa.show-sql=false"})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BookingApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired BookingRepository bookings;
    @Autowired UserRepository users;
    @Autowired JwtService tokens;
    @MockitoBean HotelReservationClient hotel;
    @TestBean(name = "rsaPublicKey", methodName = "publicKey") RSAPublicKey rsaPublicKey;
    @TestBean(name = "rsaPrivateKey", methodName = "privateKey") RSAPrivateKey rsaPrivateKey;
    static final KeyPair KEYS = keys();
    static KeyPair keys() {
        try { var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); return generator.generateKeyPair(); }
        catch (GeneralSecurityException ex) { throw new ExceptionInInitializerError(ex); }
    }
    static RSAPublicKey publicKey() { return (RSAPublicKey) KEYS.getPublic(); }
    static RSAPrivateKey privateKey() { return (RSAPrivateKey) KEYS.getPrivate(); }

    @BeforeEach void clean() {
        bookings.deleteAll(); users.deleteAll();
        when(hotel.reserve(any())).thenAnswer(i -> response(i.getArgument(0), ReservationStatus.HELD));
        when(hotel.confirm(any())).thenAnswer(i -> response(i.getArgument(0), ReservationStatus.CONFIRMED));
        when(hotel.cancel(any())).thenAnswer(i -> response(i.getArgument(0), ReservationStatus.RELEASED));
    }
    private ReservationResponse response(CreateReservationRequest r, ReservationStatus status) {
        return new ReservationResponse(r.bookingId(), r.roomId(), r.startDate(), r.endDate(), status);
    }
    private String user(String username) throws Exception {
        String credentials = json.writeValueAsString(Map.of("username", username, "password", "integration-password"));
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(credentials)).andExpect(status().isCreated());
        String login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(credentials))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(login).path("accessToken").asText();
    }
    private static final String REQUEST = """
        {"roomId":2,"startDate":"2027-01-01","endDate":"2027-01-03","requestId":"mvc-real-services"}
        """;

    @Test void realSecurityPersistenceAndSagaComposeIntoOwnerScopedLifecycle() throws Exception {
        String alice = user("alice"), bob = user("bob");
        String body = mvc.perform(post("/api/bookings").header("Authorization", "Bearer " + alice)
                .contentType(MediaType.APPLICATION_JSON).content(REQUEST)).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CONFIRMED")).andReturn().getResponse().getContentAsString();
        long id = json.readTree(body).path("id").asLong();
        mvc.perform(post("/api/bookings").header("Authorization", "Bearer " + alice).contentType(MediaType.APPLICATION_JSON).content(REQUEST))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        assertEquals(1, bookings.count());
        verify(hotel, times(1)).reserve(any());
        verify(hotel, times(1)).confirm(any());
        mvc.perform(get("/api/bookings/{id}", id).header("Authorization", "Bearer " + bob)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/bookings/{id}", id).header("Authorization", "Bearer " + bob)).andExpect(status().isNotFound());
        mvc.perform(get("/api/bookings").header("Authorization", "Bearer " + bob)).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(delete("/api/bookings/{id}", id).header("Authorization", "Bearer " + alice)).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED")).andExpect(jsonPath("$.cancellationRequested").value(false));
        mvc.perform(delete("/api/bookings/{id}", id).header("Authorization", "Bearer " + alice)).andExpect(status().isOk());
        verify(hotel, times(1)).cancel(any());
        assertEquals("CANCELLED", bookings.findById(id).orElseThrow().getStatus().name());
    }

    @Test void businessRejectionProducesCommonErrorAndPersistedCancelledState() throws Exception {
        String token = user("alice");
        doThrow(new RemoteBusinessConflictException("ROOM_UNAVAILABLE", "Unavailable")).when(hotel).reserve(any());
        when(hotel.getStatus(any())).thenThrow(new RemoteReservationNotFoundException(1L));
        when(hotel.release(any())).thenAnswer(i -> response(i.getArgument(0), ReservationStatus.RELEASED));
        String body = mvc.perform(post("/api/bookings").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(REQUEST)).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("BOOKING_CANCELLED")).andExpect(jsonPath("$.status").value(409))
            .andReturn().getResponse().getContentAsString();
        long id = json.readTree(body).path("fields").path("bookingId").asLong();
        mvc.perform(get("/api/bookings/{id}", id).header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        verify(hotel).release(any());
    }

    @Test void validationAndServiceRoleFailBeforeAnyReservation() throws Exception {
        String token = user("alice");
        mvc.perform(post("/api/bookings").header("Authorization", "Bearer " + tokens.generateServiceToken())
            .contentType(MediaType.APPLICATION_JSON).content(REQUEST)).andExpect(status().isForbidden());
        mvc.perform(post("/api/bookings").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
            .content(REQUEST.replace("2027-01-03", "2026-12-31"))).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertEquals(0, bookings.count());
        verifyNoInteractions(hotel);
    }
}
