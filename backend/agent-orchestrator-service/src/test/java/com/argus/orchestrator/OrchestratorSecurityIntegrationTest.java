package com.argus.orchestrator;

import com.argus.orchestrator.agent.DecisionPolicy;
import com.argus.orchestrator.client.CaseServiceClient;
import com.argus.orchestrator.client.PolicyClient;
import com.argus.orchestrator.client.ToolClient;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
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

/**
 * Integration test that drives a request into the SECURED orchestrator with a REAL HS256
 * JWT (signed with the shared secret auth/gateway use), proving the end-to-end RBAC chain:
 *   - no token              -> 401
 *   - valid ANALYST token   -> 202, the agent loop runs
 *   - the caller's bearer token is PROPAGATED on the internal service-to-service tool call
 *
 * The downstream tool/case clients are mocked so no real network is needed, but the
 * propagation of the Authorization header is verified explicitly.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrchestratorSecurityIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Value("${argus.jwt.secret}")
    private String secret;

    @MockitoBean
    private ToolClient toolClient;

    @MockitoBean
    private CaseServiceClient caseServiceClient;

    @MockitoBean
    private PolicyClient policyClient;

    private String token(String role) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader(JWSAlgorithm.HS256),
                new JWTClaimsSet.Builder()
                        .subject("analyst-jane")
                        .claim("role", role)
                        .issueTime(new Date())
                        .expirationTime(new Date(System.currentTimeMillis() + 3600_000))
                        .build());
        jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        return "Bearer " + jwt.serialize();
    }

    @Test
    void unauthenticatedSubmitIs401() throws Exception {
        mvc.perform(post("/api/investigations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"address\":\"0xabc\",\"runSync\":true}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void analystTokenDrivesLoopAndPropagatesBearerToTools() throws Exception {
        when(policyClient.fetchDecisionPolicy(any())).thenReturn(DecisionPolicy.defaults());
        // Minimal stub so the local agent can complete in one screening + scoring path.
        when(toolClient.invoke(eq("sanctions_screen"), any(), any())).thenReturn(Map.of(
                "addressesChecked", 1, "hitCount", 0, "directHit", false, "hits", List.of()));
        when(toolClient.invoke(eq("address_profile"), any(), any())).thenReturn(Map.of(
                "address", "0xabc", "totalInflowUsd", 100.0, "totalOutflowUsd", 50.0,
                "counterpartyCount", 1, "txCount", 1));
        when(toolClient.invoke(eq("risk_rules"), any(), any())).thenReturn(Map.of(
                "address", "0xabc", "riskScore", 0, "riskBand", "MINIMAL", "firedRules", List.of()));

        String bearer = token("ANALYST");
        mvc.perform(post("/api/investigations")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"address\":\"0xabc\",\"runSync\":true}"))
                .andExpect(status().isAccepted());

        // The caller's exact bearer token must be forwarded on the internal tool call.
        verify(toolClient, atLeastOnce()).invoke(eq("sanctions_screen"), any(), eq(bearer));
        verify(caseServiceClient).mirrorCase(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                any(), any(), any(), any(), eq(bearer));
    }
}
