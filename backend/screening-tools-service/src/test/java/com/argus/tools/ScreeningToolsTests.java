package com.argus.tools;

import com.argus.tools.dto.ToolDtos.RiskRulesRequest;
import com.argus.tools.dto.ToolDtos.RiskRulesResponse;
import com.argus.tools.dto.ToolDtos.SanctionsScreenRequest;
import com.argus.tools.dto.ToolDtos.SanctionsScreenResponse;
import com.argus.tools.dto.ToolDtos.TraceRequest;
import com.argus.tools.dto.ToolDtos.TraceResponse;
import com.argus.tools.service.RiskRulesService;
import com.argus.tools.service.SanctionsScreenService;
import com.argus.tools.service.TraceTransactionsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class ScreeningToolsTests {

    @Autowired
    private SanctionsScreenService sanctionsScreenService;
    @Autowired
    private TraceTransactionsService traceTransactionsService;
    @Autowired
    private RiskRulesService riskRulesService;

    @Test
    void directlySanctionedAddressIsDetected() {
        SanctionsScreenResponse r = sanctionsScreenService.screen(
                new SanctionsScreenRequest(List.of("0xbadc0de000000000000000000000000000000bad")));
        assertTrue(r.directHit());
        assertEquals(1, r.hitCount());
    }

    @Test
    void cleanAddressHasNoHit() {
        SanctionsScreenResponse r = sanctionsScreenService.screen(
                new SanctionsScreenRequest(List.of("0xc1ean000000000000000000000000000000c1ean")));
        assertFalse(r.directHit());
        assertEquals(0, r.hitCount());
    }

    @Test
    void traceFindsOneHopMixerExposure() {
        TraceResponse r = traceTransactionsService.trace(
                new TraceRequest("0xc0ffee00000000000000000000000000000c0ffee", 3));
        assertTrue(r.exposureFound());
        // the mixer is a direct (1-hop) counterparty
        assertTrue(r.flaggedExposures().stream().anyMatch(e -> e.hopsAway() == 1));
    }

    @Test
    void cleanWalletTraceFindsNoExposure() {
        TraceResponse r = traceTransactionsService.trace(
                new TraceRequest("0xc1ean000000000000000000000000000000c1ean", 3));
        assertFalse(r.exposureFound());
    }

    @Test
    void riskRulesScoreHighOnDirectSanctions() {
        RiskRulesResponse r = riskRulesService.evaluate(new RiskRulesRequest(
                "0xbadc0de000000000000000000000000000000bad",
                500_000.0, 400_000.0, 3, true, null));
        assertEquals("HIGH", r.riskBand());
        assertTrue(r.riskScore() >= 60);
    }
}
