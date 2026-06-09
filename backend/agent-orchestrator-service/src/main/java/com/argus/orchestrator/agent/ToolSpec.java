package com.argus.orchestrator.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Declarative description of a tool the agent may call. This is what we render into the
 * LLM's tool/function {@code input_schema}, and what the local provider reasons over.
 *
 * <p>Each parameter carries a real JSON-schema {@link ParamSpec} (type, optional element
 * type for arrays, description, required flag) so the emitted schema is correctly typed —
 * e.g. {@code sanctions_screen.addresses} is an array of strings and {@code maxHops} is an
 * integer, rather than everything being coerced to {@code "string"}. Wrong-typed tool
 * schemas cause real Claude tool-use to pass arguments the tools can't parse.
 *
 * @param name        stable tool id (must match the screening-tools-service route)
 * @param description natural-language purpose, used by the LLM to choose
 * @param parameters  ordered map of parameter name -> typed schema fragment
 */
public record ToolSpec(
        String name,
        String description,
        Map<String, ParamSpec> parameters) {

    /**
     * A single typed parameter in a tool's input schema.
     *
     * @param type        JSON-schema type: "string" | "integer" | "number" | "boolean" | "array"
     * @param itemsType   for {@code type == "array"}, the element type (e.g. "string"); else null
     * @param description human-readable description for the model
     * @param required    whether the parameter must be supplied
     */
    public record ParamSpec(String type, String itemsType, String description, boolean required) {

        public static ParamSpec required(String type, String description) {
            return new ParamSpec(type, null, description, true);
        }

        public static ParamSpec optional(String type, String description) {
            return new ParamSpec(type, null, description, false);
        }

        public static ParamSpec requiredArray(String itemsType, String description) {
            return new ParamSpec("array", itemsType, description, true);
        }

        /** Renders this param as a JSON-schema property fragment. */
        public Map<String, Object> toSchemaFragment() {
            Map<String, Object> fragment = new LinkedHashMap<>();
            fragment.put("type", type);
            if ("array".equals(type)) {
                fragment.put("items", Map.of("type", itemsType == null ? "string" : itemsType));
            }
            fragment.put("description", description);
            return fragment;
        }
    }

    /** The fixed catalog the agent is allowed to use. Kept in sync with screening-tools-service. */
    public static List<ToolSpec> defaultCatalog() {
        return List.of(
                new ToolSpec("sanctions_screen",
                        "Check one or more addresses against the sanctions/watchlist. "
                                + "Returns hits and whether the primary address is a direct hit.",
                        ordered(Map.entry("addresses",
                                ParamSpec.requiredArray("string",
                                        "wallet addresses to screen")))),
                new ToolSpec("address_profile",
                        "Get aggregate inflow/outflow USD, counterparty count and tx count for an address.",
                        ordered(Map.entry("address",
                                ParamSpec.required("string", "the wallet address to profile")))),
                new ToolSpec("trace_transactions",
                        "Walk the transaction graph up to maxHops from an address and surface any "
                                + "exposure to flagged/sanctioned addresses, with the path.",
                        ordered(
                                Map.entry("address",
                                        ParamSpec.required("string", "root wallet address")),
                                Map.entry("maxHops",
                                        ParamSpec.optional("integer", "how many hops to walk, 1-6")))),
                new ToolSpec("risk_rules",
                        "Evaluate AML threshold rules over facts gathered so far and return a "
                                + "risk score (0-100), band and the rules that fired.",
                        ordered(
                                Map.entry("address",
                                        ParamSpec.required("string", "the subject address")),
                                Map.entry("totalInflowUsd",
                                        ParamSpec.optional("number", "aggregate inflow in USD")),
                                Map.entry("totalOutflowUsd",
                                        ParamSpec.optional("number", "aggregate outflow in USD")),
                                Map.entry("counterpartyCount",
                                        ParamSpec.optional("integer", "distinct counterparties")),
                                Map.entry("sanctionsDirectHit",
                                        ParamSpec.optional("boolean",
                                                "true if the address is itself sanctioned")),
                                Map.entry("minHopsToFlagged",
                                        ParamSpec.optional("integer",
                                                "fewest hops to a flagged address, or null"))))
        );
    }

    /** Builds an insertion-ordered parameter map (order matters for a stable emitted schema). */
    @SafeVarargs
    private static Map<String, ParamSpec> ordered(Map.Entry<String, ParamSpec>... entries) {
        Map<String, ParamSpec> map = new LinkedHashMap<>();
        for (Map.Entry<String, ParamSpec> e : entries) {
            map.put(e.getKey(), e.getValue());
        }
        return map;
    }

    /** The names of the required parameters, in declaration order. */
    public List<String> requiredParameterNames() {
        return parameters().entrySet().stream()
                .filter(e -> e.getValue().required())
                .map(Map.Entry::getKey)
                .toList();
    }
}
