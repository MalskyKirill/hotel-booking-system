package ru.mephi.api;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiErrorContractTest {
    MockMvc mvc;
    @BeforeEach void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new Probe(), new ApiErrorController())
            .setControllerAdvice(new CommonApiExceptionHandler(), new ApiSecurityExceptionHandler()).build();
    }
    @ParameterizedTest
    @CsvSource({"/test/query,MISSING_PARAMETER", "/test/query?id=bad,INVALID_PARAMETER", "/test/conflict,CONFLICT"})
    void usesStableCodesAndSchema(String path, String code) throws Exception {
        mvc.perform(get(path)).andExpect(status().is(path.contains("conflict") ? 409 : 400))
            .andExpect(jsonPath("$.code").value(code)).andExpect(jsonPath("$.timestamp").isString())
            .andExpect(jsonPath("$.path").value(path.split("\\?")[0]))
            .andExpect(jsonPath("$.fields").isMap()).andExpect(jsonPath("$.error").isString());
    }
    @Test void malformedBodyDoesNotEchoInput() throws Exception {
        mvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"private-input\", broken"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"))
            .andExpect(content().string(not(containsString("private-input"))));
    }
    @Test void unsupportedMethodKeepsAllowHeader() throws Exception {
        mvc.perform(post("/test/query")).andExpect(status().isMethodNotAllowed())
            .andExpect(header().string("Allow", containsString("GET")))
            .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }
    @Test void unknownFailureDoesNotExposeExceptionMessage() throws Exception {
        mvc.perform(get("/test/failure")).andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(content().string(not(containsString("private-input"))))
            .andExpect(content().string(not(containsString("IllegalStateException"))));
    }
    @ParameterizedTest @CsvSource({"404,NOT_FOUND", "500,INTERNAL_ERROR", "503,SERVICE_UNAVAILABLE"})
    void servletFallbackUsesSameContract(int status, String code) throws Exception {
        mvc.perform(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, status)
                .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/original"))
            .andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code))
            .andExpect(jsonPath("$.path").value("/original")).andExpect(jsonPath("$.fields").isMap());
    }
    @Test void errorContractRemainsJsonWhenClientRequestsAnotherFormat() throws Exception {
        mvc.perform(get("/test/conflict").accept(MediaType.APPLICATION_XML))
            .andExpect(status().isConflict()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("CONFLICT"));
    }
    @Test void methodSecurityDenialUsesSameContract() throws Exception {
        mvc.perform(get("/test/denied")).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
            .andExpect(jsonPath("$.path").value("/test/denied"));
    }
    @RestController static class Probe {
        @GetMapping("/test/denied") void denied() { throw new org.springframework.security.access.AccessDeniedException("private-input"); }
        @GetMapping("/test/query") String query(@RequestParam Long id) { return id.toString(); }
        @PostMapping("/test/body") String body(@RequestBody Map<String,Object> body) { return "ok"; }
        @GetMapping("/test/conflict") void conflict() { throw new ResponseStatusException(HttpStatus.CONFLICT, "Conflict"); }
        @GetMapping("/test/failure") void failure() { throw new IllegalStateException("private-input"); }
    }
}
