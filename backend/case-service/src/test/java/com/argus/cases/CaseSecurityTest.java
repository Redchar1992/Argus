package com.argus.cases;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Proves user and workload identities have separate case-service capabilities. */
@SpringBootTest
@AutoConfigureMockMvc
class CaseSecurityTest {

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

    private String workloadToken(String audience, String actor) {
        return token(WORKLOAD_KEYS, "urn:argus:workload", "agent-orchestrator", audience,
                JwtSecurity.WORKLOAD_TOKEN_TYPE, "SERVICE", actor);
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
    void unauthenticatedCaseReadIs401() throws Exception {
        mvc.perform(get("/api/cases")).andExpect(status().isUnauthorized());
    }

    @Test
    void analystCanReadCasesButNotAuditOrPersist() throws Exception {
        mvc.perform(get("/api/cases").header("Authorization", userToken("ANALYST")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/audit").header("Authorization", userToken("ANALYST")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/cases")
                        .header("Authorization", userToken("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(caseBody("inv-user")))
                .andExpect(status().isForbidden());
    }

    @Test
    void workloadCanPersistAndSignedActorControlsAuditIdentity() throws Exception {
        mvc.perform(post("/api/cases")
                        .header("Authorization", workloadToken("argus-case-service", "analyst-jane"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(caseBody("inv-workload")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdBy").value("analyst-jane"));
    }

    @Test
    void analystCanResolveReviewCaseAndServiceCannotReviewIt() throws Exception {
        mvc.perform(post("/api/cases")
                        .header("Authorization", workloadToken("argus-case-service", "agent-orchestrator"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(caseBody("inv-review", "REVIEW")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("PENDING_REVIEW"));

        mvc.perform(post("/api/cases/inv-review/review")
                        .header("Authorization", workloadToken("argus-case-service", "agent-orchestrator"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"BLOCK\",\"note\":\"service must not self-approve\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/cases/inv-review/review")
                        .header("Authorization", userToken("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"CLEAR\",\"note\":\"Reviewed the trace and found no disqualifying evidence.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("REVIEW"))
                .andExpect(jsonPath("$.reviewStatus").value("RESOLVED"))
                .andExpect(jsonPath("$.reviewDecision").value("CLEAR"))
                .andExpect(jsonPath("$.reviewedBy").value("tester"));

        mvc.perform(post("/api/cases")
                        .header("Authorization", workloadToken("argus-case-service", "agent-retry"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(caseBody("inv-review", "REVIEW")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("RESOLVED"))
                .andExpect(jsonPath("$.reviewDecision").value("CLEAR"));

        mvc.perform(post("/api/cases/inv-review/review")
                        .header("Authorization", userToken("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"BLOCK\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void reviewerCanRequestMoreEvidenceBeforeResolving() throws Exception {
        mvc.perform(post("/api/cases")
                        .header("Authorization", workloadToken("argus-case-service", "agent-orchestrator"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(caseBody("inv-needs-info", "REVIEW")))
                .andExpect(status().isOk());

        mvc.perform(post("/api/cases/inv-needs-info/review")
                        .header("Authorization", userToken("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"REQUEST_INFO\",\"note\":\"Request counterparty origin and transfer rationale.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("NEEDS_INFO"))
                .andExpect(jsonPath("$.reviewDecision").doesNotExist());

        mvc.perform(post("/api/cases/inv-needs-info/review")
                        .header("Authorization", userToken("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"BLOCK\",\"note\":\"Requested evidence was not provided.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("RESOLVED"))
                .andExpect(jsonPath("$.reviewDecision").value("BLOCK"));
    }

    @Test
    void invalidReviewActionIsRejected() throws Exception {
        mvc.perform(post("/api/cases")
                        .header("Authorization", workloadToken("argus-case-service", "agent-orchestrator"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(caseBody("inv-invalid-review", "REVIEW")))
                .andExpect(status().isOk());

        mvc.perform(post("/api/cases/inv-invalid-review/review")
                        .header("Authorization", userToken("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"BLOCK\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/cases/inv-invalid-review/review")
                        .header("Authorization", userToken("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"APPROVE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void workloadForWrongAudienceIs401() throws Exception {
        mvc.perform(post("/api/cases")
                        .header("Authorization", workloadToken("argus-screening-tools", "analyst-jane"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(caseBody("inv-wrong-audience")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void analystCannotEditPolicyButAdminCan() throws Exception {
        mvc.perform(put("/api/policies/blockThreshold")
                        .header("Authorization", userToken("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":70,\"actor\":\"spoofed\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/policies/blockThreshold")
                        .header("Authorization", userToken("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":70,\"actor\":\"spoofed\"}"))
                .andExpect(status().isOk());
    }

    private static String caseBody(String id) {
        return caseBody(id, "CLEAR");
    }

    private static String caseBody(String id, String decision) {
        return "{\"id\":\"" + id + "\",\"subjectAddress\":\"0xabc\","
                + "\"decision\":\"" + decision + "\",\"riskScore\":0,\"riskBand\":\"MINIMAL\","
                + "\"summary\":\"ok\",\"riskFactorsJson\":\"[]\","
                + "\"createdBy\":\"attacker-controlled\"}";
    }
}
