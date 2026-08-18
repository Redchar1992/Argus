package com.argus.tools;

import com.argus.security.jwt.JwtSecurity;
import com.argus.security.jwt.KeyPurpose;
import com.argus.security.jwt.RsaKeyRing;
import com.nimbusds.jwt.JWTClaimsSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Date;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Proves user and workload tokens have distinct audiences and capabilities. */
@SpringBootTest
@AutoConfigureMockMvc
class ToolsSecurityTest {

    private static final RsaKeyRing AUTH_KEYS = RsaKeyRing.signing(
            "", "", "", KeyPurpose.AUTH, false);
    private static final RsaKeyRing WORKLOAD_KEYS = RsaKeyRing.signing(
            "", "", "", KeyPurpose.WORKLOAD, false);

    @Autowired
    private MockMvc mvc;

    private String userToken(String role) {
        return token(AUTH_KEYS, "urn:argus:auth", "tester", "argus-admin-api",
                JwtSecurity.USER_TOKEN_TYPE, role, null);
    }

    private String workloadToken(String audience) {
        return token(WORKLOAD_KEYS, "urn:argus:workload", "agent-orchestrator", audience,
                JwtSecurity.WORKLOAD_TOKEN_TYPE, "SERVICE", "analyst-jane");
    }

    private String token(RsaKeyRing keys, String issuer, String subject, String audience,
                         String type, String role, String actor) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuer).subject(subject).audience(audience)
                .claim("token_type", type).claim("role", role)
                .issueTime(Date.from(now)).notBeforeTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(3600)));
        if (actor != null) claims.claim("actor", actor);
        return "Bearer " + keys.sign(claims.build());
    }

    @Test
    void unauthenticatedToolCallIs401() throws Exception {
        mvc.perform(post("/api/tools/sanctions_screen")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addresses\":[\"0xabc\"]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void analystUserTokenCannotExecuteInternalTool() throws Exception {
        mvc.perform(post("/api/tools/sanctions_screen")
                        .header("Authorization", userToken("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addresses\":[\"0xabc\"]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void workloadTokenCanExecuteScreening() throws Exception {
        mvc.perform(post("/api/tools/sanctions_screen")
                        .header("Authorization", workloadToken("argus-screening-tools"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addresses\":[\"0xabc\"]}"))
                .andExpect(status().isOk());
    }

    @Test
    void workloadTokenForAnotherAudienceIs401() throws Exception {
        mvc.perform(post("/api/tools/sanctions_screen")
                        .header("Authorization", workloadToken("argus-case-service"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addresses\":[\"0xabc\"]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void analystCanReadButCannotMutateCatalog() throws Exception {
        mvc.perform(get("/api/tools/catalog").header("Authorization", userToken("ANALYST")))
                .andExpect(status().isOk());
        mvc.perform(put("/api/tools/catalog/sanctions_screen")
                        .header("Authorization", userToken("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanMutateToolCatalog() throws Exception {
        mvc.perform(put("/api/tools/catalog/sanctions_screen")
                        .header("Authorization", userToken("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk());
    }
}
