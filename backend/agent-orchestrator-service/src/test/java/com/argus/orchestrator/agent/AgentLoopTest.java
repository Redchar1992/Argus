package com.argus.orchestrator.agent;

import com.argus.orchestrator.client.CaseServiceClient;
import com.argus.orchestrator.client.PolicyClient;
import com.argus.orchestrator.client.ToolClient;
import com.argus.orchestrator.llm.LocalRuleAgentProvider;
import com.argus.orchestrator.model.Investigation;
import com.argus.orchestrator.repository.InMemoryInvestigationStore;
import com.argus.orchestrator.repository.InvestigationStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Drives the full plan-act-observe loop with the LOCAL provider against a stubbed
 * ToolClient, proving the agent genuinely chains tools and that different wallets
 * take different paths to different decisions. No Spring context, no network.
 */
class AgentLoopTest {

    private AgentOrchestrator buildOrchestrator(ToolClient toolClient) {
        InvestigationStore store = new InMemoryInvestigationStore();
        CaseServiceClient caseClient = mock(CaseServiceClient.class);
        doNothing().when(caseClient).mirrorCase(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                any(), any(), any(), any());
        PolicyClient policyClient = mock(PolicyClient.class);
        when(policyClient.fetchDecisionPolicy(any())).thenReturn(DecisionPolicy.defaults());
        return new AgentOrchestrator(new LocalRuleAgentProvider(), toolClient, store, caseClient,
                policyClient, 8);
    }

    /** Builds an orchestrator whose policy client returns the given custom bands. */
    private AgentOrchestrator buildOrchestrator(ToolClient toolClient, DecisionPolicy policy) {
        InvestigationStore store = new InMemoryInvestigationStore();
        CaseServiceClient caseClient = mock(CaseServiceClient.class);
        doNothing().when(caseClient).mirrorCase(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                any(), any(), any(), any());
        PolicyClient policyClient = mock(PolicyClient.class);
        when(policyClient.fetchDecisionPolicy(any())).thenReturn(policy);
        return new AgentOrchestrator(new LocalRuleAgentProvider(), toolClient, store, caseClient,
                policyClient, 8);
    }

    @Test
    void sanctionedWalletIsBlockedAndChainsTools() {
        ToolClient tools = mock(ToolClient.class);
        when(tools.invoke(eq("sanctions_screen"), any(), any())).thenReturn(Map.of(
                "addressesChecked", 1, "hitCount", 1, "directHit", true,
                "hits", List.of(Map.of("address", "0xbad", "entity", "Lazarus", "severity", 95))));
        when(tools.invoke(eq("address_profile"), any(), any())).thenReturn(Map.of(
                "address", "0xbad", "totalInflowUsd", 500000.0, "totalOutflowUsd", 400000.0,
                "counterpartyCount", 4, "txCount", 6));
        when(tools.invoke(eq("trace_transactions"), any(), any())).thenReturn(Map.of(
                "rootAddress", "0xbad", "exposureFound", true,
                "flaggedExposures", List.of(Map.of("flaggedAddress", "0xmix", "entity", "Mixer",
                        "hopsAway", 1, "amountUsdOnPath", 250000.0, "path", List.of()))));
        when(tools.invoke(eq("risk_rules"), any(), any())).thenReturn(Map.of(
                "address", "0xbad", "riskScore", 95, "riskBand", "HIGH",
                "firedRules", List.of(Map.of("ruleId", "R1_DIRECT_SANCTIONS",
                        "description", "Address is itself on a sanctions list", "points", 60))));

        AgentOrchestrator orch = buildOrchestrator(tools);
        orch.createInvestigation("inv1", "0xbad", "tester");
        Investigation inv = orch.run("inv1");

        assertEquals("COMPLETED", inv.getStatus());
        assertEquals("BLOCK", inv.getDecision());
        assertEquals(95, inv.getRiskScore());
        // It must have ACTUALLY chained all four tools then finished.
        assertTrue(inv.getSteps().stream().anyMatch(s -> "sanctions_screen".equals(s.getToolName())));
        assertTrue(inv.getSteps().stream().anyMatch(s -> "address_profile".equals(s.getToolName())));
        assertTrue(inv.getSteps().stream().anyMatch(s -> "trace_transactions".equals(s.getToolName())));
        assertTrue(inv.getSteps().stream().anyMatch(s -> "risk_rules".equals(s.getToolName())));
        assertTrue(inv.getSteps().stream().anyMatch(s -> "FINISH".equals(s.getPhase())));
        assertNotNull(inv.getRiskFactors());
    }

    @Test
    void tinyCleanWalletSkipsTraceAndClears() {
        ToolClient tools = mock(ToolClient.class);
        when(tools.invoke(eq("sanctions_screen"), any(), any())).thenReturn(Map.of(
                "addressesChecked", 1, "hitCount", 0, "directHit", false, "hits", List.of()));
        // Tiny wallet: low volume, few counterparties -> agent should SKIP tracing.
        when(tools.invoke(eq("address_profile"), any(), any())).thenReturn(Map.of(
                "address", "0xclean", "totalInflowUsd", 1500.0, "totalOutflowUsd", 500.0,
                "counterpartyCount", 1, "txCount", 2));
        when(tools.invoke(eq("risk_rules"), any(), any())).thenReturn(Map.of(
                "address", "0xclean", "riskScore", 0, "riskBand", "MINIMAL",
                "firedRules", List.of(Map.of("ruleId", "R0_NO_FLAGS",
                        "description", "No AML rules fired", "points", 0))));

        AgentOrchestrator orch = buildOrchestrator(tools);
        orch.createInvestigation("inv2", "0xclean", "tester");
        Investigation inv = orch.run("inv2");

        assertEquals("CLEAR", inv.getDecision());
        // The judgement: tracing was NOT performed for this tiny clean wallet.
        assertFalse(inv.getSteps().stream().anyMatch(s -> "trace_transactions".equals(s.getToolName())));
    }

    /**
     * FAIL-CLOSED (issue #3): if a REQUIRED tool (here risk_rules) returns an error instead
     * of a valid observation, the decision must NOT be CLEAR even though score=0/directHit=false.
     */
    @Test
    void missingRequiredEvidenceFailsClosedToReviewNotClear() {
        ToolClient tools = mock(ToolClient.class);
        when(tools.invoke(eq("sanctions_screen"), any(), any())).thenReturn(Map.of(
                "addressesChecked", 1, "hitCount", 0, "directHit", false, "hits", List.of()));
        when(tools.invoke(eq("address_profile"), any(), any())).thenReturn(Map.of(
                "address", "0xq", "totalInflowUsd", 1000.0, "totalOutflowUsd", 200.0,
                "counterpartyCount", 1, "txCount", 2));
        // risk_rules is unavailable / disabled -> tool client surfaces an error observation.
        when(tools.invoke(eq("risk_rules"), any(), any())).thenReturn(Map.of(
                "error", "tool_unreachable", "tool", "risk_rules"));

        AgentOrchestrator orch = buildOrchestrator(tools);
        orch.createInvestigation("inv3", "0xq", "tester");
        Investigation inv = orch.run("inv3");

        assertNotEquals("CLEAR", inv.getDecision());
        assertEquals("REVIEW", inv.getDecision());
        assertTrue(inv.getRiskFactors().stream()
                .anyMatch(f -> f.toLowerCase().contains("incomplete evidence")
                        && f.contains("risk_rules")));
    }

    /**
     * POLICY-DRIVEN BANDS (issue #4): the same borderline score lands in a DIFFERENT band
     * when the admin lowers the review threshold. With defaults (review>=30) a score of 20
     * is CLEAR; tighten the policy to review>=15 and the same wallet becomes REVIEW.
     */
    @Test
    void editingPolicyMovesBorderlineScoreIntoADifferentBand() {
        ToolClient tools = mock(ToolClient.class);
        when(tools.invoke(eq("sanctions_screen"), any(), any())).thenReturn(Map.of(
                "addressesChecked", 1, "hitCount", 0, "directHit", false, "hits", List.of()));
        when(tools.invoke(eq("address_profile"), any(), any())).thenReturn(Map.of(
                "address", "0xborder", "totalInflowUsd", 1000.0, "totalOutflowUsd", 200.0,
                "counterpartyCount", 1, "txCount", 2));
        when(tools.invoke(eq("risk_rules"), any(), any())).thenReturn(Map.of(
                "address", "0xborder", "riskScore", 20, "riskBand", "LOW",
                "firedRules", List.of(Map.of("ruleId", "R3_SOME", "description", "minor", "points", 20))));

        // Default bands (60/30): score 20 < 30 -> CLEAR.
        AgentOrchestrator defaultOrch = buildOrchestrator(tools, DecisionPolicy.defaults());
        defaultOrch.createInvestigation("invA", "0xborder", "tester");
        assertEquals("CLEAR", defaultOrch.run("invA").getDecision());

        // Admin tightens review threshold to 15: the SAME score 20 now lands in REVIEW.
        AgentOrchestrator strictOrch = buildOrchestrator(tools, new DecisionPolicy(60, 15));
        strictOrch.createInvestigation("invB", "0xborder", "tester");
        assertEquals("REVIEW", strictOrch.run("invB").getDecision());
    }
}
