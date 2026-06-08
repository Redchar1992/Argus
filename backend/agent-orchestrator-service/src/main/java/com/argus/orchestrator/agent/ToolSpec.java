package com.argus.orchestrator.agent;

import java.util.List;
import java.util.Map;

/**
 * Declarative description of a tool the agent may call. This is what we render
 * into the LLM's tool/function schema, and what the local provider reasons over.
 *
 * @param name        stable tool id (must match the screening-tools-service route)
 * @param description natural-language purpose, used by the LLM to choose
 * @param parameters  JSON-schema-like map of parameter name -> description
 */
public record ToolSpec(
        String name,
        String description,
        Map<String, String> parameters) {

    /** The fixed catalog the agent is allowed to use. Kept in sync with screening-tools-service. */
    public static List<ToolSpec> defaultCatalog() {
        return List.of(
                new ToolSpec("sanctions_screen",
                        "Check one or more addresses against the sanctions/watchlist. "
                                + "Returns hits and whether the primary address is a direct hit.",
                        Map.of("addresses", "array of wallet addresses to screen (string[])")),
                new ToolSpec("address_profile",
                        "Get aggregate inflow/outflow USD, counterparty count and tx count for an address.",
                        Map.of("address", "the wallet address to profile (string)")),
                new ToolSpec("trace_transactions",
                        "Walk the transaction graph up to maxHops from an address and surface any "
                                + "exposure to flagged/sanctioned addresses, with the path.",
                        Map.of("address", "root wallet address (string)",
                                "maxHops", "how many hops to walk, 1-6 (integer)")),
                new ToolSpec("risk_rules",
                        "Evaluate AML threshold rules over facts gathered so far and return a "
                                + "risk score (0-100), band and the rules that fired.",
                        Map.of("address", "the subject address (string)",
                                "totalInflowUsd", "aggregate inflow in USD (number, optional)",
                                "totalOutflowUsd", "aggregate outflow in USD (number, optional)",
                                "counterpartyCount", "distinct counterparties (integer, optional)",
                                "sanctionsDirectHit", "true if the address is itself sanctioned (boolean, optional)",
                                "minHopsToFlagged", "fewest hops to a flagged address, or null (integer, optional)"))
        );
    }
}
