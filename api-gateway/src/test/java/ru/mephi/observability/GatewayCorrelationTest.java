package ru.mephi.observability;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "eureka.client.enabled=false")
@AutoConfigureMockMvc
class GatewayCorrelationTest {
    @Autowired MockMvc mvc;
    @Test void retainsCorrelationOnMissingRoute() throws Exception {
        mvc.perform(get("/missing").header(TraceContext.HEADER, "gateway-missing"))
            .andExpect(status().isNotFound()).andExpect(header().string(TraceContext.HEADER, "gateway-missing"));
    }
    @Test void retainsCorrelationWhenDiscoveryHasNoBackend() throws Exception {
        mvc.perform(get("/api/hotels").header(TraceContext.HEADER, "gateway-unavailable"))
            .andExpect(status().isServiceUnavailable()).andExpect(header().string(TraceContext.HEADER, "gateway-unavailable"));
    }
}
