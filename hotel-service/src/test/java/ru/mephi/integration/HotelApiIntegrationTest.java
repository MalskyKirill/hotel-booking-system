package ru.mephi.integration;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"eureka.client.enabled=false", "spring.datasource.url=jdbc:h2:mem:hotel-api-tests", "spring.jpa.show-sql=false"})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HotelApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @TestBean(name = "rsaPublicKey", methodName = "publicKey") RSAPublicKey rsaPublicKey;
    static final KeyPair KEYS = keys();
    static KeyPair keys() {
        try { var g = KeyPairGenerator.getInstance("RSA"); g.initialize(2048); return g.generateKeyPair(); }
        catch (GeneralSecurityException ex) { throw new ExceptionInInitializerError(ex); }
    }
    static RSAPublicKey publicKey() { return (RSAPublicKey) KEYS.getPublic(); }
    private String token(String role) throws Exception {
        var encoder = Base64.getUrlEncoder().withoutPadding();
        long now = Instant.now().getEpochSecond();
        String message = encoder.encodeToString(json.writeValueAsBytes(Map.of("alg", "RS256"))) + "." +
            encoder.encodeToString(json.writeValueAsBytes(Map.of("iss", "hotel-booking-system", "sub", "mvc-user", "role", role,
                "iat", now, "exp", now + 300)));
        var signature = Signature.getInstance("SHA256withRSA"); signature.initSign(KEYS.getPrivate());
        signature.update(message.getBytes(StandardCharsets.US_ASCII));
        return "Bearer " + message + "." + encoder.encodeToString(signature.sign());
    }
    @Test void roleChecksAndCrudUseRealSecurityAndDatabase() throws Exception {
        String data = "{\"name\":\"MVC hotel\",\"address\":\"Local\"}";
        mvc.perform(post("/api/hotels").header("Authorization", token("USER")).contentType(MediaType.APPLICATION_JSON).content(data))
            .andExpect(status().isForbidden());
        String body = mvc.perform(post("/api/hotels").header("Authorization", token("ADMIN")).contentType(MediaType.APPLICATION_JSON).content(data))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = json.readTree(body).path("id").asLong();
        mvc.perform(get("/api/hotels/{id}", id).header("Authorization", token("USER"))).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("MVC hotel"));
        mvc.perform(delete("/api/hotels/{id}", id).header("Authorization", token("USER"))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/hotels/{id}", id).header("Authorization", token("ADMIN"))).andExpect(status().isNoContent());
        mvc.perform(get("/api/hotels/{id}", id).header("Authorization", token("USER"))).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("HOTEL_NOT_FOUND"));
    }
    @Test void openApiDescribesPublicRolesAndExcludesInternalRoutes() throws Exception {
        String body = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
            .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
            .andReturn().getResponse().getContentAsString();
        JsonNode spec = json.readTree(body);
        var paths = spec.path("paths");
        paths.fieldNames().forEachRemaining(path -> assertTrue(path.startsWith("/api/")));
        assertTrue(paths.path("/api/hotels").path("post").path("responses").has("201"));
        assertTrue(paths.path("/api/hotels/{id}").path("delete").path("responses").has("204"));
        assertEquals("ADMIN", paths.path("/api/admin/rooms").path("get").path("x-required-roles").get(0).asText());
        assertEquals(7, spec.path("components").path("schemas").path("ApiError").path("required").size());
        mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/api/hotels")).andExpect(status().isUnauthorized());
    }
}
