package ru.mephi.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.mephi.controller.*;
import ru.mephi.service.*;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({HotelController.class, RoomController.class})
@Import(SecurityConfig.class)
class CatalogSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean(name = "publicJwtDecoder") JwtDecoder publicDecoder;
    @MockitoBean(name = "internalJwtDecoder") JwtDecoder internalDecoder;
    @MockitoBean HotelService hotels;
    @MockitoBean RoomService rooms;
    void role(String role) {
        when(publicDecoder.decode("test-token")).thenReturn(Jwt.withTokenValue("test-token").header("alg", "RS256")
            .subject("alice").claim("role", role).build());
    }
    @Test void anonymousCatalogRequestHasJsonChallenge() throws Exception {
        mvc.perform(get("/api/hotels")).andExpect(status().isUnauthorized())
            .andExpect(header().string("WWW-Authenticate", "Bearer"))
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.path").value("/api/hotels")).andExpect(jsonPath("$.fields").isMap());
        verifyNoInteractions(hotels);
    }
    @ParameterizedTest @ValueSource(strings = {"USER", "ADMIN"})
    void userAndAdminCanReadCatalog(String role) throws Exception {
        role(role); when(hotels.getAll()).thenReturn(List.of());
        mvc.perform(get("/api/hotels").header("Authorization", "Bearer test-token")).andExpect(status().isOk());
    }
    @ParameterizedTest @ValueSource(strings = {"SERVICE", "UNKNOWN"})
    void otherRolesCannotReadCatalog(String role) throws Exception {
        role(role);
        mvc.perform(get("/api/hotels").header("Authorization", "Bearer test-token"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        verifyNoInteractions(hotels);
    }
    @Test void invalidTokenUsesJsonContract() throws Exception {
        when(publicDecoder.decode("invalid")).thenThrow(new BadJwtException("private-input"));
        mvc.perform(get("/api/hotels").header("Authorization", "Bearer invalid"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.message").value("Authentication required or token invalid"));
    }
    @Test void internalChainStillRejectsUserRole() throws Exception {
        when(internalDecoder.decode("test-token")).thenReturn(Jwt.withTokenValue("test-token").header("alg", "RS256")
            .subject("alice").claim("role", "USER").build());
        mvc.perform(post("/internal/reservations").header("Authorization", "Bearer test-token"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }
}
