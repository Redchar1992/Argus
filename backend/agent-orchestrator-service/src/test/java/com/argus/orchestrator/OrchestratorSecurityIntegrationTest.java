package com.argus.orchestrator;

import com.argus.orchestrator.agent.DecisionPolicy;
import com.argus.orchestrator.client.CaseServiceClient;
import com.argus.orchestrator.client.PolicyClient;
import com.argus.orchestrator.client.ToolClient;
import com.argus.security.jwt.JwtSecurity;
import com.argus.security.jwt.KeyPurpose;
import com.argus.security.jwt.RsaKeyRing;
import com.nimbusds.jwt.JWTClaimsSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercises real RS256 user-token validation and verifies user credentials are not propagated. */
@SpringBootTest
@AutoConfigureMockMvc
class OrchestratorSecurityIntegrationTest {

    private static final RsaKeyRing AUTH_KEYS = RsaKeyRing.signing(
            "", "", "", KeyPurpose.AUTH, false);

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ToolClient toolClient;

    @MockitoBean
    private CaseServiceClient caseServiceClient;

    @MockitoBean
    private PolicyClient policyClient;

    private String token(String role, String audience) {
        Instant now = Instant.now();
        return "Bearer " + AUTH_KEYS.sign(new JWTClaimsSet.Builder()
                .issuer("urn:argus:auth")
                .subject("analyst-jane")
                .audience(audience)
                .claim("token_type", JwtSecurity.USER_TOKEN_TYPE)
                .claim("role", role)
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(3600)))
                .build());
    }

    @Test
    void unauthenticatedSubmitIs401() throws Exception {
        mvc.perform(post("/api/investigations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"address\":\"0xabc\",\"runSync\":true}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongAudienceIs401() throws Exception {
        mvc.perform(post("/api/investigations")
                        .header("Authorization", token("ANALYST", "argus-admin-api"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"address\":\"0xabc\",\"runSync\":true}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void analystTokenDrivesLoopUsingSignedActorInsteadOfCallerBearer() throws Exception {
        when(policyClient.fetchDecisionPolicy(any())).thenReturn(DecisionPolicy.defaults());
        when(toolClient.invoke(eq("sanctions_screen"), any(), any())).thenReturn(Map.of(
                "addressesChecked", 1, "hitCount", 0, "directHit", false, "hits", List.of()));
        when(toolClient.invoke(eq("address_profile"), any(), any())).thenReturn(Map.of(
                "address", "0xabc", "totalInflowUsd", 100.0, "totalOutflowUsd", 50.0,
                "counterpartyCount", 1, "txCount", 1));
        when(toolClient.invoke(eq("risk_rules"), any(), any())).thenReturn(Map.of(
                "address", "0xabc", "riskScore", 0, "riskBand", "MINIMAL", "firedRules", List.of()));

        mvc.perform(post("/api/investigations")
                        .header("Authorization", token("ANALYST", "argus-orchestrator"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"address\":\"0xabc\",\"runSync\":true}"))
                .andExpect(status().isAccepted());

        verify(policyClient).fetchDecisionPolicy("analyst-jane");
        verify(toolClient, atLeastOnce()).invoke(eq("sanctions_screen"), any(), eq("analyst-jane"));
        verify(caseServiceClient).mirrorCase(any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt(), any(), any(), any(), eq("analyst-jane"));
    }
}
