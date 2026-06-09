package com.argus.orchestrator.llm;

import com.argus.orchestrator.agent.ToolSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the Anthropic tool {@code input_schema} is correctly TYPED (issue #5): addresses
 * is an array-of-string, hop depth is an integer, and the finish tool is well-formed. The
 * old code emitted {@code {"type":"string"}} for every parameter, which real Claude tool-use
 * would fill with wrong-typed args the tools couldn't parse.
 */
class ToolSchemaTest {

    @SuppressWarnings("unchecked")
    private Map<String, Object> schemaFor(List<Map<String, Object>> schemas, String name) {
        return schemas.stream()
                .filter(s -> name.equals(s.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No tool schema named " + name));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sanctionsScreenAddressesIsArrayOfString() {
        List<Map<String, Object>> schemas =
                AnthropicLlmProvider.buildToolSchemas(ToolSpec.defaultCatalog());

        Map<String, Object> sanctions = schemaFor(schemas, "sanctions_screen");
        Map<String, Object> input = (Map<String, Object>) sanctions.get("input_schema");
        Map<String, Object> props = (Map<String, Object>) input.get("properties");
        Map<String, Object> addresses = (Map<String, Object>) props.get("addresses");

        assertEquals("array", addresses.get("type"));
        Map<String, Object> items = (Map<String, Object>) addresses.get("items");
        assertNotNull(items, "array schema must declare items");
        assertEquals("string", items.get("type"));

        // addresses is required.
        List<String> required = (List<String>) input.get("required");
        assertTrue(required.contains("addresses"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void traceMaxHopsIsIntegerAndAddressIsString() {
        List<Map<String, Object>> schemas =
                AnthropicLlmProvider.buildToolSchemas(ToolSpec.defaultCatalog());

        Map<String, Object> trace = schemaFor(schemas, "trace_transactions");
        Map<String, Object> input = (Map<String, Object>) trace.get("input_schema");
        Map<String, Object> props = (Map<String, Object>) input.get("properties");

        assertEquals("integer", ((Map<String, Object>) props.get("maxHops")).get("type"));
        assertEquals("string", ((Map<String, Object>) props.get("address")).get("type"));

        // address is required, maxHops is optional.
        List<String> required = (List<String>) input.get("required");
        assertTrue(required.contains("address"));
        assertTrue(!required.contains("maxHops"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void finishToolIsWellFormed() {
        List<Map<String, Object>> schemas =
                AnthropicLlmProvider.buildToolSchemas(ToolSpec.defaultCatalog());

        Map<String, Object> finish = schemaFor(schemas, "finish_investigation");
        Map<String, Object> input = (Map<String, Object>) finish.get("input_schema");
        Map<String, Object> props = (Map<String, Object>) input.get("properties");

        assertEquals("object", input.get("type"));
        assertEquals("string", ((Map<String, Object>) props.get("decision")).get("type"));
        assertEquals("integer", ((Map<String, Object>) props.get("riskScore")).get("type"));
        // riskFactors is an array of strings.
        Map<String, Object> factors = (Map<String, Object>) props.get("riskFactors");
        assertEquals("array", factors.get("type"));
        assertEquals("string", ((Map<String, Object>) factors.get("items")).get("type"));

        List<String> required = (List<String>) input.get("required");
        assertTrue(required.containsAll(List.of("decision", "riskScore", "riskBand", "riskFactors")));
    }
}
