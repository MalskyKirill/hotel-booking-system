package ru.mephi.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.mephi.config.SecurityConfig;
import ru.mephi.controller.*;
import ru.mephi.dto.RoomPage;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({RoomAdminController.class, RoomStatisticsController.class})
@Import(SecurityConfig.class)
class RoomAdminSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean(name = "publicJwtDecoder") JwtDecoder publicDecoder;
    @MockitoBean(name = "internalJwtDecoder") JwtDecoder internalDecoder;
    @MockitoBean RoomAdminService rooms;
    @MockitoBean RoomService creator;
    @MockitoBean RoomStatisticsService statistics;
    void role(String role) {
        when(publicDecoder.decode("test-token")).thenReturn(Jwt.withTokenValue("test-token").header("alg", "RS256")
            .subject("admin").claim("role", role).build());
    }
    @Test void anonymousCannotReadAdminCatalog() throws Exception {
        mvc.perform(get("/api/admin/rooms")).andExpect(status().isUnauthorized());
    }
    @ParameterizedTest @ValueSource(strings = {"USER", "SERVICE"})
    void nonAdminCannotReadStatisticsOrChangeRoom(String role) throws Exception {
        role(role);
        mvc.perform(get("/api/admin/rooms").header("Authorization", "Bearer test-token")).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/rooms/statistics").param("startDate", "2027-01-01").param("endDate", "2027-01-03")
                .header("Authorization", "Bearer test-token")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/admin/rooms/1").header("Authorization", "Bearer test-token")).andExpect(status().isForbidden());
        verifyNoInteractions(rooms, creator, statistics);
    }
    @Test void adminCanReadIncludingUnavailableRooms() throws Exception {
        role("ADMIN"); when(rooms.list(null, 0, 20)).thenReturn(new RoomPage(List.of(), 0, 20, 0, 0));
        mvc.perform(get("/api/admin/rooms").header("Authorization", "Bearer test-token"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }
}
