package com.argus.cases;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves case-service is no longer unauthenticated: reads require a valid JWT, the
 * compliance-sensitive audit trail and policy mutation are ADMIN-only. Tokens are REAL
 * HS256 JWTs signed with the shared secret (exercises the real decoder + role mapping).
 */
@SpringBootTest
@AutoConfigureMockMvc
class CaseSecurityTest {

    @Autowired
    private MockMvc mvc;

    @Value("${argus.jwt.secret}")
    private String secret;

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
    void unauthenticatedCaseReadIs401() throws Exception {
        mvc.perform(get("/api/cases"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void analystCanReadCases() throws Exception {
        mvc.perform(get("/api/cases").header("Authorization", token("ANALYST")))
                .andExpect(status().isOk());
    }

    @Test
    void analystCannotReadAuditTrail() throws Exception {
        mvc.perform(get("/api/audit").header("Authorization", token("ANALYST")))
                .andExpect(status().isForbidden());
    }

    @Test
    void analystCannotEditPolicy() throws Exception {
        mvc.perform(put("/api/policies/blockThreshold")
                        .header("Authorization", token("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":70,\"actor\":\"analyst\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanEditPolicy() throws Exception {
        mvc.perform(put("/api/policies/blockThreshold")
                        .header("Authorization", token("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":70,\"actor\":\"admin\"}"))
                .andExpect(status().isOk());
    }
}
