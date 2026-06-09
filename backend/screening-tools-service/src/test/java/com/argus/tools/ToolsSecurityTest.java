package com.argus.tools;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves screening-tools-service is no longer "裸奔": every tool endpoint now requires a
 * valid JWT, and the admin-gated catalog mutation rejects an analyst. Tokens are REAL
 * HS256 JWTs signed with the shared secret, so the actual NimbusJwtDecoder + role
 * mapping are exercised end-to-end (not stubbed).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ToolsSecurityTest {

    @Autowired
    private MockMvc mvc;

    @Value("${argus.jwt.secret}")
    private String secret;

    /** Mint a real HS256 token carrying the given role, exactly like auth-service does. */
    private String token(String role) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader(JWSAlgorithm.HS256),
                new JWTClaimsSet.Builder()
                        .subject("tester")
                        .claim("role", role)
                        .issueTime(new Date())
                        .expirationTime(new Date(System.currentTimeMillis() + 3600_000))
                        .build());
        jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        return "Bearer " + jwt.serialize();
    }

    @Test
    void unauthenticatedToolCallIs401() throws Exception {
        mvc.perform(post("/api/tools/sanctions_screen")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addresses\":[\"0xabc\"]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void analystTokenCanRunScreening() throws Exception {
        mvc.perform(post("/api/tools/sanctions_screen")
                        .header("Authorization", token("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addresses\":[\"0xabc\"]}"))
                .andExpect(status().isOk());
    }

    @Test
    void analystCannotMutateToolCatalog() throws Exception {
        mvc.perform(put("/api/tools/catalog/sanctions_screen")
                        .header("Authorization", token("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanMutateToolCatalog() throws Exception {
        mvc.perform(put("/api/tools/catalog/sanctions_screen")
                        .header("Authorization", token("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk());
    }
}
