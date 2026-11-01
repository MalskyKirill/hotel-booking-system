package ru.mephi.config;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.mephi.controller.UserAdminController;
import ru.mephi.dto.*;
import ru.mephi.model.Role;
import ru.mephi.service.UserAdminService;
import java.util.List;
import java.util.stream.Stream;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UserAdminController.class)
@Import(SecurityConfig.class)
class UserAdminSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean UserAdminService users;
    void role(String role) {
        when(decoder.decode("test-token")).thenReturn(Jwt.withTokenValue("test-token").header("alg", "RS256")
            .subject("admin").claim("role", role).build());
    }
    @Test void anonymousIsUnauthorized() throws Exception {
        mvc.perform(get("/api/admin/users")).andExpect(status().isUnauthorized());
        verifyNoInteractions(users);
    }
    @ParameterizedTest @ValueSource(strings = {"USER", "SERVICE"})
    void otherRolesCannotReadOrCreateUsers(String role) throws Exception {
        role(role);
        mvc.perform(get("/api/admin/users").header("Authorization", "Bearer test-token")).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/users").header("Authorization", "Bearer test-token")
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(users);
    }
    @Test void adminResponseDoesNotContainPassword() throws Exception {
        role("ADMIN");
        when(users.create(any())).thenReturn(new UserResponse(12L, "alice", Role.USER));
        mvc.perform(post("/api/admin/users").header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"username":"alice","password":"TestUser123!","role":"USER"}
                    """))
            .andExpect(status().isCreated()).andExpect(header().string("Location", "/api/admin/users/12"))
            .andExpect(jsonPath("$.username").value("alice")).andExpect(jsonPath("$.password").doesNotExist());
        when(users.list(0, 20)).thenReturn(new UserPage(List.of(new UserResponse(12L, "alice", Role.USER)), 0, 20, 1, 1));
        mvc.perform(get("/api/admin/users").header("Authorization", "Bearer test-token"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].password").doesNotExist());
    }
    @ParameterizedTest @MethodSource("invalidCreate")
    void invalidInputDoesNotReachService(String json) throws Exception {
        role("ADMIN");
        mvc.perform(post("/api/admin/users").header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON).content(json))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(users);
    }
    static Stream<String> invalidCreate() {
        return Stream.of("{}", "{\"username\":\"alice\",\"password\":\"short\",\"role\":\"USER\"}",
            "{\"username\":\"alice\",\"password\":\"TestUser123!\",\"role\":\"SERVICE\"}",
            "{\"username\":\"alice\",\"password\":\"" + "я".repeat(40) + "\",\"role\":\"USER\"}");
    }
    @ParameterizedTest @ValueSource(strings = {"{}", "{\"password\":\"        \"}"})
    void emptyOrBlankPasswordUpdateIsRejected(String json) throws Exception {
        role("ADMIN");
        mvc.perform(patch("/api/admin/users/12").header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON).content(json))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(users);
    }
}
