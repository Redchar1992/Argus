package com.storyforge;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("local")
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "app.auth.rate-limit.enabled=true",
        "app.auth.rate-limit.registration.max-attempts=1",
        "app.auth.rate-limit.registration.window=1h",
        "app.auth.rate-limit.login.max-attempts=2",
        "app.auth.rate-limit.login.window=1h",
        "app.auth.rate-limit.max-entries=100"
})
class AuthRateLimitIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void registrationUsesForwardedClientIpAndReturnsStructured429() throws Exception {
        register("rate-register-a", "203.0.113.11")
                .andExpect(status().isCreated());

        register("rate-register-b", "203.0.113.11")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("AUTH_RATE_LIMITED"));

        // MockMvc's socket address is unchanged, so success proves forwarded headers are honored.
        register("rate-register-c", "203.0.113.12")
                .andExpect(status().isCreated());
    }

    @Test
    void loginLimitsEachIpAndNormalizedUsernamePairWithoutBlockingAnotherIp() throws Exception {
        register("rate-login-user", "203.0.113.21")
                .andExpect(status().isCreated());

        login("rate-login-user", "wrong-password", "198.51.100.21")
                .andExpect(status().isUnauthorized());
        login(" RATE-LOGIN-USER ", "wrong-password", "198.51.100.21")
                .andExpect(status().isUnauthorized());
        login("rate-login-user", "password123", "198.51.100.21")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("AUTH_RATE_LIMITED"));

        login("rate-login-user", "password123", "198.51.100.22")
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions register(String username, String forwardedIp)
            throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                .header("X-Forwarded-For", forwardedIp)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "username": "%s",
                          "password": "password123",
                          "privacyAccepted": true
                        }
                        """.formatted(username)));
    }

    private org.springframework.test.web.servlet.ResultActions login(
            String username,
            String password,
            String forwardedIp
    ) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .header("X-Forwarded-For", forwardedIp)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username":"%s","password":"%s"}
                        """.formatted(username, password)));
    }
}
