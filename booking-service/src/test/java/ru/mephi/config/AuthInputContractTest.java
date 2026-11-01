package ru.mephi.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.mephi.controller.AuthController;
import ru.mephi.service.AuthService;
import java.util.stream.Stream;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
class AuthInputContractTest {
    @Autowired MockMvc mvc;
    @MockitoBean AuthService auth;
    @MockitoBean JwtDecoder decoder;
    @ParameterizedTest @MethodSource("invalidRegistrations")
    void registrationRejectsInputBeyondDatabaseAndBcryptLimits(String json) throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(json))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fields").isMap());
        verifyNoInteractions(auth);
    }
    static Stream<String> invalidRegistrations() {
        return Stream.of("{\"username\":\"" + "a".repeat(101) + "\",\"password\":\"TestUser123!\"}",
            "{\"username\":\"alice\",\"password\":\"" + "a".repeat(73) + "\"}",
            "{\"username\":\"alice\",\"password\":\"" + "я".repeat(40) + "\"}");
    }
    @Test void incorrectCredentialsHaveSafeJsonChallenge() throws Exception {
        when(auth.login(any())).thenThrow(new BadCredentialsException("private-input"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice\",\"password\":\"incorrect\"}"))
            .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"))
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
            .andExpect(jsonPath("$.message").value("Invalid credentials"));
    }
}
