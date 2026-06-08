package com.argus.orchestrator.llm;

import com.argus.orchestrator.agent.AgentAction;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Deterministic, no-API-key "brain" for the agent. This is a REAL tool-selecting
 * loop, not a canned script: on each call it inspects what has already been
 * observed and decides the next sensible action. The plan it follows is:
 *
 *   1. If we have never screened the subject -> call sanctions_screen.
 *   2. If we have a sanctions result but no profile -> call address_profile.
 *   3. If we have a profile but have not traced the graph -> call trace_transactions.
 *      (We only bother tracing if there is reason to: either a sanctions hit, or
 *       meaningful volume/fan-out — otherwise we can finish early. This is the
 *       agent exercising judgement, not a fixed pipeline.)
 *   4. Once we have enough evidence (or an early-exit clean signal) -> call
 *      risk_rules to score, then FINISH with a decision derived from the score.
 *
 * Because step 3 is conditional on prior observations, two different wallets take
 * genuinely different paths through the tools — that is the point.
 */
@Component
@ConditionalOnProperty(name = "argus.llm.provider", havingValue = "local", matchIfMissing = true)
public class LocalRuleAgentProvider implements LlmProvider {

    @Override
    public String name() {
        return "local-rule-agent";
    }

    @Override
    public AgentAction nextAction(AgentContext ctx) {
        String subject = ctx.getSubjectAddress();

        // Step 1: always establish whether the subject itself is sanctioned first.
        if (!ctx.hasRun("sanctions_screen")) {
            return AgentAction.callTool(
                    "Starting the investigation. First I need to know if the subject wallet is "
                            + "itself on a sanctions list, since a direct hit dominates the decision.",
                    "sanctions_screen",
                    Map.of("addresses", List.of(subject)));
        }

        Map<String, Object> sanctions = ctx.latestObservation("sanctions_screen");
        boolean directHit = asBool(sanctions.get("directHit"));

        // Step 2: understand the wallet's activity profile (volume, counterparties).
        if (!ctx.hasRun("address_profile")) {
            String reason = directHit
                    ? "The subject is directly sanctioned. I will still profile its activity to "
                      + "quantify exposure for the report."
                    : "No direct sanctions hit. I need the wallet's volume and counterparty profile "
                      + "to judge whether deeper tracing is warranted.";
            return AgentAction.callTool(reason, "address_profile", Map.of("address", subject));
        }

        Map<String, Object> profile = ctx.latestObservation("address_profile");
        double inflow = asDouble(profile.get("totalInflowUsd"));
        double outflow = asDouble(profile.get("totalOutflowUsd"));
        int counterparties = asInt(profile.get("counterpartyCount"));
        double volume = inflow + outflow;

        // Step 3: decide whether to trace the graph. Trace if directly sanctioned,
        // OR if there is non-trivial activity worth following. A tiny clean wallet
        // can skip tracing — the agent uses judgement here.
        boolean activityWarrantsTrace = volume >= 10_000 || counterparties >= 3;
        if (!ctx.hasRun("trace_transactions") && (directHit || activityWarrantsTrace)) {
            String reason = directHit
                    ? "Tracing the graph to document how funds flow to/from this sanctioned wallet."
                    : "Activity is non-trivial (volume=$" + (long) volume + ", counterparties="
                      + counterparties + "). Tracing the graph to check for hidden exposure to "
                      + "flagged addresses.";
            return AgentAction.callTool(reason, "trace_transactions",
                    Map.of("address", subject, "maxHops", 3));
        }

        // Step 4: score with risk_rules once, feeding everything we learned.
        if (!ctx.hasRun("risk_rules")) {
            Integer minHops = minHopsToFlagged(ctx.latestObservation("trace_transactions"));
            Map<String, Object> args = new java.util.HashMap<>();
            args.put("address", subject);
            args.put("totalInflowUsd", inflow);
            args.put("totalOutflowUsd", outflow);
            args.put("counterpartyCount", counterparties);
            args.put("sanctionsDirectHit", directHit);
            args.put("minHopsToFlagged", minHops);
            String reason = "I have screening, profile" + (ctx.hasRun("trace_transactions")
                    ? " and trace" : "") + " results. Running the AML rule engine to turn these "
                    + "facts into a defensible risk score.";
            return AgentAction.callTool(reason, "risk_rules", args);
        }

        // FINISH: synthesize the decision from the risk_rules score + policy bands.
        Map<String, Object> risk = ctx.latestObservation("risk_rules");
        int score = asInt(risk.get("riskScore"));
        String band = String.valueOf(risk.getOrDefault("riskBand", "MINIMAL"));
        String decision = decisionFor(score, directHit);
        List<String> factors = buildFactors(directHit, ctx.latestObservation("trace_transactions"), risk);

        String thought = "Evidence gathering is complete. Final risk score is " + score
                + " (" + band + "). Issuing decision: " + decision + ".";
        return AgentAction.finish(thought, decision, score, band, factors);
    }

    private String decisionFor(int score, boolean directHit) {
        if (directHit || score >= 60) {
            return "BLOCK";
        }
        if (score >= 30) {
            return "REVIEW";
        }
        return "CLEAR";
    }

    @SuppressWarnings("unchecked")
    private List<String> buildFactors(boolean directHit, Map<String, Object> trace, Map<String, Object> risk) {
        List<String> factors = new ArrayList<>();
        if (directHit) {
            factors.add("Subject wallet is directly on a sanctions list.");
        }
        if (trace != null && asBool(trace.get("exposureFound"))) {
            Object exposures = trace.get("flaggedExposures");
            if (exposures instanceof List<?> list && !list.isEmpty()) {
                Object first = list.get(0);
                if (first instanceof Map<?, ?> ex) {
                    factors.add("Exposure to flagged entity '" + ex.get("entity") + "' at "
                            + ex.get("hopsAway") + " hop(s).");
                }
            }
        }
        if (risk != null && risk.get("firedRules") instanceof List<?> rules) {
            for (Object r : rules) {
                if (r instanceof Map<?, ?> rule && !"R0_NO_FLAGS".equals(rule.get("ruleId"))) {
                    factors.add(rule.get("description") + " (+" + rule.get("points") + ")");
                }
            }
        }
        if (factors.isEmpty()) {
            factors.add("No AML risk factors detected on available evidence.");
        }
        return factors;
    }

    @SuppressWarnings("unchecked")
    private Integer minHopsToFlagged(Map<String, Object> trace) {
        if (trace == null || !asBool(trace.get("exposureFound"))) {
            return null;
        }
        Object exposures = trace.get("flaggedExposures");
        if (!(exposures instanceof List<?> list)) {
            return null;
        }
        Integer min = null;
        for (Object o : list) {
            if (o instanceof Map<?, ?> ex) {
                int hops = asInt(ex.get("hopsAway"));
                if (min == null || hops < min) {
                    min = hops;
                }
            }
        }
        return min;
    }

    private static boolean asBool(Object o) {
        return o instanceof Boolean b && b;
    }

    private static int asInt(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static double asDouble(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }
}
