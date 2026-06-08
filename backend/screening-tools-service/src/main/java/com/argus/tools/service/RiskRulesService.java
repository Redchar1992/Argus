package com.argus.tools.service;

import com.argus.tools.dto.ToolDtos.FiredRule;
import com.argus.tools.dto.ToolDtos.RiskRulesRequest;
import com.argus.tools.dto.ToolDtos.RiskRulesResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * risk_rules tool: evaluates a transparent, points-based AML rule set over the
 * facts the agent has gathered so far. Each fired rule carries its own evidence
 * so the final decision is fully explainable/auditable.
 *
 * Thresholds are illustrative but modelled on common AML heuristics (structuring,
 * direct sanctions exposure, proximity to flagged entities, fan-out behaviour).
 */
@Service
public class RiskRulesService {

    public RiskRulesResponse evaluate(RiskRulesRequest req) {
        List<FiredRule> fired = new ArrayList<>();
        int score = 0;

        boolean directHit = Boolean.TRUE.equals(req.sanctionsDirectHit());
        if (directHit) {
            fired.add(new FiredRule("R1_DIRECT_SANCTIONS", "Address is itself on a sanctions list",
                    60, "sanctionsDirectHit=true"));
            score += 60;
        }

        Integer minHops = req.minHopsToFlagged();
        if (minHops != null && minHops >= 1) {
            if (minHops == 1) {
                fired.add(new FiredRule("R2_DIRECT_COUNTERPARTY",
                        "Transacts directly (1 hop) with a flagged address", 35,
                        "minHopsToFlagged=1"));
                score += 35;
            } else if (minHops == 2) {
                fired.add(new FiredRule("R3_SECONDHOP_EXPOSURE",
                        "Indirect exposure (2 hops) to a flagged address", 20,
                        "minHopsToFlagged=2"));
                score += 20;
            } else {
                fired.add(new FiredRule("R4_DISTANT_EXPOSURE",
                        "Distant exposure (>=3 hops) to a flagged address", 8,
                        "minHopsToFlagged=" + minHops));
                score += 8;
            }
        }

        double inflow = req.totalInflowUsd() == null ? 0 : req.totalInflowUsd();
        double outflow = req.totalOutflowUsd() == null ? 0 : req.totalOutflowUsd();
        double volume = inflow + outflow;
        if (volume >= 1_000_000) {
            fired.add(new FiredRule("R5_HIGH_VOLUME",
                    "Aggregate transfer volume exceeds $1,000,000", 12,
                    "volumeUsd=" + (long) volume));
            score += 12;
        }

        int counterparties = req.counterpartyCount() == null ? 0 : req.counterpartyCount();
        if (counterparties >= 8) {
            fired.add(new FiredRule("R6_HIGH_FANOUT",
                    "High counterparty fan-out (>=8) suggests layering", 10,
                    "counterpartyCount=" + counterparties));
            score += 10;
        }

        // Structuring heuristic: many counterparties but modest average ticket size.
        if (counterparties >= 6 && volume > 0 && (volume / counterparties) < 9_000) {
            fired.add(new FiredRule("R7_STRUCTURING_PATTERN",
                    "Many small transfers (avg < $9k) across many counterparties", 15,
                    "avgTicketUsd=" + Math.round(volume / Math.max(counterparties, 1))));
            score += 15;
        }

        if (fired.isEmpty()) {
            fired.add(new FiredRule("R0_NO_FLAGS", "No AML rules fired on available evidence", 0,
                    "clean"));
        }

        score = Math.min(score, 100);
        String band = bandOf(score);
        return new RiskRulesResponse(req.address(), score, band, fired);
    }

    private String bandOf(int score) {
        if (score >= 60) {
            return "HIGH";
        }
        if (score >= 30) {
            return "MEDIUM";
        }
        if (score >= 10) {
            return "LOW";
        }
        return "MINIMAL";
    }
}
