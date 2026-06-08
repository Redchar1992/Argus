package com.argus.orchestrator.agent;

import com.argus.orchestrator.client.CaseServiceClient;
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
        return new AgentOrchestrator(new LocalRuleAgentProvider(), toolClient, store, caseClient, 8);
    }

    @Test
    void sanctionedWalletIsBlockedAndChainsTools() {
        ToolClient tools = mock(ToolClient.class);
        when(tools.invoke(eq("sanctions_screen"), any())).thenReturn(Map.of(
                "addressesChecked", 1, "hitCount", 1, "directHit", true,
                "hits", List.of(Map.of("address", "0xbad", "entity", "Lazarus", "severity", 95))));
        when(tools.invoke(eq("address_profile"), any())).thenReturn(Map.of(
                "address", "0xbad", "totalInflowUsd", 500000.0, "totalOutflowUsd", 400000.0,
                "counterpartyCount", 4, "txCount", 6));
        when(tools.invoke(eq("trace_transactions"), any())).thenReturn(Map.of(
                "rootAddress", "0xbad", "exposureFound", true,
                "flaggedExposures", List.of(Map.of("flaggedAddress", "0xmix", "entity", "Mixer",
                        "hopsAway", 1, "amountUsdOnPath", 250000.0, "path", List.of()))));
        when(tools.invoke(eq("risk_rules"), any())).thenReturn(Map.of(
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
        when(tools.invoke(eq("sanctions_screen"), any())).thenReturn(Map.of(
                "addressesChecked", 1, "hitCount", 0, "directHit", false, "hits", List.of()));
        // Tiny wallet: low volume, few counterparties -> agent should SKIP tracing.
        when(tools.invoke(eq("address_profile"), any())).thenReturn(Map.of(
                "address", "0xclean", "totalInflowUsd", 1500.0, "totalOutflowUsd", 500.0,
                "counterpartyCount", 1, "txCount", 2));
        when(tools.invoke(eq("risk_rules"), any())).thenReturn(Map.of(
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
}
