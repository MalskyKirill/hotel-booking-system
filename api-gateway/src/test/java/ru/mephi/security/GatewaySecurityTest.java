package ru.mephi.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import ru.mephi.observability.TraceContext;

@SpringBootTest(properties = "eureka.client.enabled=false")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class GatewaySecurityTest extends GatewaySecurityTestSupport {
  @Autowired MockMvc mvc;

  @ParameterizedTest
  @CsvSource({
    "USER,GET,/api/hotels",
    "ADMIN,POST,/api/hotels",
    "USER,GET,/api/rooms",
    "USER,POST,/api/bookings",
    "ADMIN,GET,/api/admin/users"
  })
  void allowedRequestsReachRoutingWithoutADiscoveredBackend(String role, String method, String path)
      throws Exception {
    mvc.perform(
            request(HttpMethod.valueOf(method), path)
                .header("Authorization", "Bearer " + token(role)))
        .andExpect(status().isServiceUnavailable());
  }

  @ParameterizedTest
  @CsvSource({
    "USER,POST,/api/hotels", "USER,PUT,/api/rooms/1", "USER,PATCH,/api/rooms/1",
    "USER,DELETE,/api/hotels/1", "USER,GET,/api/admin/users", "USER,GET,/api/admin/rooms",
    "USER,GET,/actuator/gateway/routes", "SERVICE,GET,/api/bookings", "OTHER,GET,/api/rooms"
  })
  void rejectsRolesBeforeRoutingAndKeepsErrorContract(String role, String method, String path)
      throws Exception {
    mvc.perform(
            request(HttpMethod.valueOf(method), path)
                .header("Authorization", "Bearer " + token(role))
                .header(TraceContext.HEADER, "gateway-forbidden"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
        .andExpect(jsonPath("$.status").value(403))
        .andExpect(jsonPath("$.fields").isMap())
        .andExpect(header().string(TraceContext.HEADER, "gateway-forbidden"));
  }

  static Stream<String> invalidTokens() {
    return Stream.of(
        "malformed",
        token("USER", "another-issuer", Instant.now().plusSeconds(3600), false),
        token("USER", "hotel-booking-system", Instant.now().minusSeconds(120), false),
        token("USER", "hotel-booking-system", Instant.now().plusSeconds(3600), true));
  }

  @ParameterizedTest(name = "{index}: invalid JWT rejected")
  @MethodSource("invalidTokens")
  void validatesSignatureIssuerAndExpiration(String jwt) throws Exception {
    mvc.perform(
            get("/api/hotels")
                .header("Authorization", "Bearer " + jwt)
                .header(TraceContext.HEADER, "gateway-invalid-token"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("WWW-Authenticate", "Bearer"))
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
        .andExpect(header().string(TraceContext.HEADER, "gateway-invalid-token"));
  }

  @Test
  void missingTokenUsesCommonUnauthorizedResponse() throws Exception {
    mvc.perform(get("/api/bookings").header(TraceContext.HEADER, "gateway-no-token"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("WWW-Authenticate", "Bearer"))
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
        .andExpect(header().string(TraceContext.HEADER, "gateway-no-token"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"/api/auth/register", "/api/auth/login"})
  void publicAuthRequestsReachRoutingWithoutToken(String path) throws Exception {
    mvc.perform(post(path)).andExpect(status().isServiceUnavailable());
  }

  @Test
  void documentationAndUnmappedInternalPathsStayPublicEvenWithUnrelatedToken() throws Exception {
    mvc.perform(get("/v3/api-docs/swagger-config").header("Authorization", "Bearer invalid"))
        .andExpect(status().isOk());
    mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    mvc.perform(
            get("/internal/reservations/1").header("Authorization", "Bearer " + token("SERVICE")))
        .andExpect(status().isNotFound());
  }
}
