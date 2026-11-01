package ru.mephi.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = DevConsoleSecurityTest.Probe.class,
    properties = {"spring.h2.console.enabled=true", "spring.h2.console.path=/h2-console"})
@Import({SecurityConfig.class, DevConsoleSecurityTest.Probe.class})
class DevConsoleSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @RestController static class Probe {
        @GetMapping("/h2-console/index") String console() { return "local console"; }
    }
    @Test void enabledConsoleDoesNotOpenOtherPaths() throws Exception {
        mvc.perform(get("/h2-console/index")).andExpect(status().isOk());
        mvc.perform(get("/h2-console-other")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/users")).andExpect(status().isUnauthorized());
    }
}
