package com.argus.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "ARGUS_REGION=test-region")
@AutoConfigureMockMvc
@AutoConfigureObservability
class MonitoringWebTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void exportsLowCardinalityIdentityMetricsAndReadiness() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"analyst\",\"password\":\"analyst12345\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("argus_identity_auth_attempts_total")))
                .andExpect(content().string(containsString("flow=\"password\"")))
                .andExpect(content().string(containsString("outcome=\"authenticated\"")))
                .andExpect(content().string(containsString("region=\"test-region\"")))
                .andExpect(content().string(not(containsString("analyst12345"))));

        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("UP")));
    }
}
