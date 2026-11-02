package ru.mephi.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.*;
import java.security.interfaces.*;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.mephi.client.HotelReservationClient;
import ru.mephi.dto.AutoReservationRequest;
import ru.mephi.dto.CreateReservationRequest;
import ru.mephi.exception.RemoteServiceUncertainException;
import ru.mephi.repository.BookingRepository;
import ru.mephi.repository.UserRepository;

@SpringBootTest(
    properties = {
      "eureka.client.enabled=false", "booking.saga.recovery.enabled=false",
      "booking.saga.circuit.enabled=false", "booking.saga.remote.initial-delay-ms=0",
      "spring.datasource.url=jdbc:h2:mem:booking-discovery-tests", "spring.jpa.show-sql=false"
    })
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HotelDiscoveryUnavailableIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired HotelReservationClient hotel;
  @Autowired BookingRepository bookings;
  @Autowired UserRepository users;

  @TestBean(name = "rsaPublicKey", methodName = "publicKey")
  RSAPublicKey rsaPublicKey;

  @TestBean(name = "rsaPrivateKey", methodName = "privateKey")
  RSAPrivateKey rsaPrivateKey;

  private static final KeyPair KEYS = keys();

  static KeyPair keys() {
    try {
      var generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (GeneralSecurityException ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  static RSAPublicKey publicKey() {
    return (RSAPublicKey) KEYS.getPublic();
  }

  static RSAPrivateKey privateKey() {
    return (RSAPrivateKey) KEYS.getPrivate();
  }

  @BeforeEach
  void clean() {
    bookings.deleteAll();
    users.deleteAll();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void missingDiscoveredInstanceDefersSagaAndCancellationInsteadOfReturning500(boolean automatic)
      throws Exception {
    LocalDate start = LocalDate.of(2027, 1, 1), end = start.plusDays(2);
    // The real Spring Cloud load-balancer has an empty SimpleDiscoveryClient. No HTTP mock.
    var failure =
        assertThrows(
            RemoteServiceUncertainException.class,
            () -> {
              if (automatic) hotel.reserveAuto(new AutoReservationRequest(99L, null, start, end));
              else hotel.reserve(new CreateReservationRequest(99L, 1L, start, end));
            });
    assertInstanceOf(IllegalStateException.class, failure.getCause());
    assertTrue(
        failure.getCause().getMessage().contains("No instances available for hotel-service"));

    String credentials =
        json.writeValueAsString(
            Map.of(
                "username", "discovery-user", "password", java.util.UUID.randomUUID().toString()));
    mvc.perform(post("/api/auth/register").contentType("application/json").content(credentials))
        .andExpect(status().isCreated());
    String login =
        mvc.perform(post("/api/auth/login").contentType("application/json").content(credentials))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String token = "Bearer " + json.readTree(login).path("accessToken").asText();
    String request =
        json.writeValueAsString(
            Map.of(
                "requestId",
                "missing-discovery",
                "autoSelect",
                automatic,
                "roomId",
                1L,
                "startDate",
                start.toString(),
                "endDate",
                end.toString()));
    String response =
        mvc.perform(
                post("/api/bookings")
                    .header("Authorization", token)
                    .contentType("application/json")
                    .content(request))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    long id = json.readTree(response).path("id").asLong();
    mvc.perform(
            post("/api/bookings")
                .header("Authorization", token)
                .contentType("application/json")
                .content(request))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.id").value(id));
    assertEquals(1, bookings.count());
    assertTrue(bookings.findById(id).orElseThrow().isSagaCompensating());
    mvc.perform(delete("/api/bookings/{id}", id).header("Authorization", token))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.cancellationRequested").value(true));
    assertTrue(bookings.findById(id).orElseThrow().isCancellationRequested());
  }
}
