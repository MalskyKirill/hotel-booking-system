package ru.mephi.observability;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static ru.mephi.observability.TraceContext.*;

class RequestCorrelationFilterTest {
    MockMvc mvc;
    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new Probe()).addFilters(new RequestCorrelationFilter()).build();
    }
    @AfterEach void clean() { MDC.clear(); }
    @Test void keepsValidIdInMdcRequestAndResponse() throws Exception {
        mvc.perform(get("/probe").header(HEADER, "request-123"))
            .andExpect(header().string(HEADER, "request-123"))
            .andExpect(jsonPath("$.mdc").value("request-123"))
            .andExpect(jsonPath("$.header").value("request-123"));
        assertNull(MDC.get(MDC_KEY));
    }
    @Test void generatesIdWhenMissingAndExposesItToTheDownstreamRequest() throws Exception {
        MvcResult result = mvc.perform(get("/probe")).andExpect(status().isOk()).andReturn();
        String id = result.getResponse().getHeader(HEADER);
        assertTrue(valid(id));
        assertTrue(result.getResponse().getContentAsString().contains(id));
        assertNull(MDC.get(MDC_KEY));
    }
    @ParameterizedTest @ValueSource(strings = {"", "contains spaces", "bad\nvalue", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void replacesInvalidId(String invalid) throws Exception {
        var result = mvc.perform(get("/probe").header(HEADER, invalid)).andExpect(status().isOk()).andReturn();
        String id = result.getResponse().getHeader(HEADER);
        assertTrue(valid(id));
        assertNotEquals(invalid, id);
        assertTrue(result.getResponse().getContentAsString().contains(id));
    }
    @Test void replacesAmbiguousRepeatedHeaders() throws Exception {
        var result = mvc.perform(get("/probe").header(HEADER, "first", "second")).andReturn();
        String id = result.getResponse().getHeader(HEADER);
        assertNotEquals("first", id); assertNotEquals("second", id); assertTrue(valid(id));
    }
    @Test void restoresOuterMdcEvenIfChainFails() {
        MDC.put(MDC_KEY, "outer");
        MDC.put("other", "retained");
        var request = new MockHttpServletRequest(); request.addHeader(HEADER, "inner");
        var response = new MockHttpServletResponse();
        assertThrows(ServletException.class, () -> new RequestCorrelationFilter().doFilter(request, response, (req, res) -> {
            assertEquals("inner", MDC.get(MDC_KEY)); throw new ServletException("test");
        }));
        assertEquals("outer", MDC.get(MDC_KEY)); assertEquals("retained", MDC.get("other"));
        assertEquals("inner", response.getHeader(HEADER));
    }
    @ParameterizedTest @ValueSource(strings = {"ERROR", "ASYNC"})
    void reusesTheOriginalAttributeOnRedispatch(String dispatcher) throws Exception {
        var request = new MockHttpServletRequest();
        request.setDispatcherType(DispatcherType.valueOf(dispatcher));
        request.setAttribute(RequestCorrelationFilter.ATTRIBUTE, "initial-request");
        var response = new MockHttpServletResponse();
        new RequestCorrelationFilter().doFilter(request, response, (req, res) -> assertEquals("initial-request", MDC.get(MDC_KEY)));
        assertEquals("initial-request", response.getHeader(HEADER));
        assertNull(MDC.get(MDC_KEY));
    }
    @RestController static class Probe {
        @GetMapping("/probe") Map<String, String> probe(@RequestHeader(HEADER) String header) {
            return Map.of("mdc", MDC.get(MDC_KEY), "header", header);
        }
    }
}
