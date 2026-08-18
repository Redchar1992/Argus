package com.argus.orchestrator.llm;

import com.argus.orchestrator.agent.AgentAction;
import com.argus.orchestrator.agent.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real LLM brain using the Anthropic Messages API with tool-use. Active when
 * argus.llm.provider=anthropic and ARGUS_ANTHROPIC_API_KEY is set.
 *
 * Request shape (Messages API):
 *   POST https://api.anthropic.com/v1/messages
 *   headers: x-api-key, anthropic-version: 2023-06-01
 *   body: { model, max_tokens, system, tools:[{name,description,input_schema}], messages:[...] }
 *
 * We expose the four screening tools PLUS a synthetic "finish_investigation" tool
 * so the model returns its final decision through the same structured tool-call
 * channel (robust parsing, no free-text decision scraping). We translate the
 * model's tool_use block into an {@link AgentAction}.
 *
 * Note: this provider returns ONE action per call. The orchestrator drives the
 * loop and replays prior tool results back to the model as the conversation
 * history, so the model has full context on every turn.
 */
@Component
@ConditionalOnProperty(name = "argus.llm.provider", havingValue = "anthropic")
public class AnthropicLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(AnthropicLlmProvider.class);
    private static final String FINISH_TOOL = "finish_investigation";

    private static final String SYSTEM_PROMPT = """
            You are Argus, an autonomous crypto-compliance investigator. Given a subject
            wallet address, investigate it using the provided tools, then issue a final
            compliance decision.

            Method:
            - Reason step by step. Call ONE tool at a time.
            - Always begin by screening the subject for direct sanctions exposure.
            - Profile the wallet's activity, and trace its transaction graph when the
              activity or any sanctions signal warrants it.
            - Use risk_rules to convert gathered facts into a defensible score.
            - When you have enough evidence, call finish_investigation with a decision of
              CLEAR, REVIEW, or BLOCK, a 0-100 riskScore, a riskBand, and the concrete
              riskFactors that justify it. A direct sanctions hit must result in BLOCK.

            FAIL CLOSED (critical): you may only return CLEAR when BOTH sanctions_screen
            AND risk_rules have run successfully and returned valid results. If either of
            those required tools is missing, failed, errored, disabled, or returns
            evidenceComplete=false because a required screening provider is unavailable, you MUST NOT
            return CLEAR — return REVIEW and cite the missing evidence in riskFactors.

            Be concise and auditable. Every tool call should have a clear purpose.
            """;

    private final WebClient webClient;
    private final ObjectMapper mapper;
    private final String model;
    private final int maxTokens;

    public AnthropicLlmProvider(
            @Value("${argus.llm.anthropic.base-url:https://api.anthropic.com}") String baseUrl,
            @Value("${ARGUS_ANTHROPIC_API_KEY:${argus.llm.anthropic.api-key:}}") String apiKey,
            @Value("${argus.llm.anthropic.model:claude-sonnet-4-6}") String model,
            @Value("${argus.llm.anthropic.max-tokens:1024}") int maxTokens,
            ObjectMapper mapper) {
        this.model = model;
        this.maxTokens = maxTokens;
        this.mapper = mapper;
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", "2023-06-01")
                .defaultHeader("content-type", "application/json")
                .build();
    }

    @Override
    public String name() {
        return "anthropic:" + model;
    }

    @Override
    public AgentAction nextAction(AgentContext ctx) {
        Map<String, Object> body = buildRequest(ctx);
        try {
            String raw = webClient.post()
                    .uri("/v1/messages")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(60))
                    .block();
            return failClosed(parseResponse(raw), ctx);
        } catch (Exception e) {
            log.error("Anthropic call failed, returning safe REVIEW: {}", e.getMessage());
            return AgentAction.finish(
                    "LLM provider error (" + e.getMessage() + "); defaulting to manual REVIEW.",
                    "REVIEW", 50, "MEDIUM",
                    List.of("Automated investigation could not complete; routed to human review."));
        }
    }

    /** Required tools that must have valid observations before a CLEAR is permitted. */
    private static final List<String> REQUIRED_TOOLS = List.of("sanctions_screen", "risk_rules");

    /**
     * Deterministic fail-closed guard layered over whatever the model returned. Even if the
     * LLM ignores the system instruction and tries to CLEAR on incomplete evidence, we
     * override the decision to REVIEW. This is defence-in-depth: the prompt asks for it, the
     * code guarantees it.
     */
    private AgentAction failClosed(AgentAction action, AgentContext ctx) {
        if (action == null || action.type() != AgentAction.Type.FINISH
                || !"CLEAR".equalsIgnoreCase(action.decision())) {
            return action;
        }
        List<String> missing = new ArrayList<>();
        for (String tool : REQUIRED_TOOLS) {
            Map<String, Object> obs = ctx.latestObservation(tool);
            if (obs == null || obs.isEmpty() || obs.containsKey("error")
                    || Boolean.FALSE.equals(obs.get("evidenceComplete"))) {
                missing.add(tool);
            }
        }
        if (missing.isEmpty()) {
            return action;
        }
        List<String> factors = new ArrayList<>(
                action.riskFactors() == null ? List.of() : action.riskFactors());
        factors.add(0, "Incomplete evidence: required tool(s) produced no valid observation ("
                + String.join(", ", missing) + "). Overriding model CLEAR to REVIEW (fail closed).");
        log.warn("Model returned CLEAR with missing required evidence {}; failing closed to REVIEW.",
                missing);
        return AgentAction.finish(action.thought(), "REVIEW",
                action.riskScore() == null ? 50 : action.riskScore(),
                action.riskBand() == null ? "MEDIUM" : action.riskBand(), factors);
    }

    private Map<String, Object> buildRequest(AgentContext ctx) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("model", model);
        req.put("max_tokens", maxTokens);
        req.put("system", SYSTEM_PROMPT);
        req.put("tools", buildToolSchemas(ctx.getTools()));
        req.put("messages", buildMessages(ctx));
        return req;
    }

    /** Package-private + static so the schema shape can be unit-tested without a live client. */
    static List<Map<String, Object>> buildToolSchemas(List<ToolSpec> tools) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ToolSpec t : tools) {
            // Emit a CORRECTLY-TYPED JSON schema per parameter (array-of-string, integer,
            // number, boolean, string) plus a required list — not everything-is-a-string.
            Map<String, Object> props = new LinkedHashMap<>();
            t.parameters().forEach((k, spec) -> props.put(k, spec.toSchemaFragment()));
            Map<String, Object> inputSchema = new LinkedHashMap<>();
            inputSchema.put("type", "object");
            inputSchema.put("properties", props);
            List<String> required = t.requiredParameterNames();
            if (!required.isEmpty()) {
                inputSchema.put("required", required);
            }
            out.add(Map.of(
                    "name", t.name(),
                    "description", t.description(),
                    "input_schema", inputSchema));
        }
        // The structured finish tool.
        out.add(Map.of(
                "name", FINISH_TOOL,
                "description", "Conclude the investigation with a final compliance decision.",
                "input_schema", Map.of(
                        "type", "object",
                        "required", List.of("decision", "riskScore", "riskBand", "riskFactors"),
                        "properties", Map.of(
                                "thought", Map.of("type", "string"),
                                "decision", Map.of("type", "string", "enum", List.of("CLEAR", "REVIEW", "BLOCK")),
                                "riskScore", Map.of("type", "integer"),
                                "riskBand", Map.of("type", "string"),
                                "riskFactors", Map.of("type", "array", "items", Map.of("type", "string"))))));
        return out;
    }

    /**
     * Rebuilds the conversation from the accumulated history so the model sees its
     * own prior tool calls and the tool results, exactly as the Messages API expects:
     * assistant tool_use blocks paired with user tool_result blocks.
     */
    private List<Map<String, Object>> buildMessages(AgentContext ctx) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content",
                "Investigate this subject wallet for compliance risk: " + ctx.getSubjectAddress()));

        int i = 0;
        for (AgentContext.ObservedCall call : ctx.getHistory()) {
            String toolUseId = "toolu_local_" + (i++);
            messages.add(Map.of("role", "assistant", "content", List.of(Map.of(
                    "type", "tool_use",
                    "id", toolUseId,
                    "name", call.toolName(),
                    "input", call.args() == null ? Map.of() : call.args()))));
            messages.add(Map.of("role", "user", "content", List.of(Map.of(
                    "type", "tool_result",
                    "tool_use_id", toolUseId,
                    "content", toJson(call.observation())))));
        }
        return messages;
    }

    private AgentAction parseResponse(String raw) {
        try {
            JsonNode root = mapper.readTree(raw);
            JsonNode content = root.path("content");
            JsonNode textThought = null;
            JsonNode toolUse = null;
            for (JsonNode block : content) {
                String type = block.path("type").asText();
                if ("text".equals(type) && textThought == null) {
                    textThought = block;
                } else if ("tool_use".equals(type)) {
                    toolUse = block;
                    break;
                }
            }
            String thought = textThought != null ? textThought.path("text").asText("") : "";

            if (toolUse == null) {
                // No tool call -> treat the text as a soft finish needing review.
                return AgentAction.finish(thought.isBlank() ? "Model returned no tool call." : thought,
                        "REVIEW", 50, "MEDIUM",
                        List.of("Model ended without a structured decision; routed to review."));
            }

            String tool = toolUse.path("name").asText();
            JsonNode input = toolUse.path("input");

            if (FINISH_TOOL.equals(tool)) {
                String t = input.path("thought").asText(thought);
                String decision = input.path("decision").asText("REVIEW");
                int score = input.path("riskScore").asInt(50);
                String band = input.path("riskBand").asText("MEDIUM");
                List<String> factors = new ArrayList<>();
                input.path("riskFactors").forEach(f -> factors.add(f.asText()));
                return AgentAction.finish(t, decision, score, band, factors);
            }

            Map<String, Object> args = mapper.convertValue(input, Map.class);
            return AgentAction.callTool(thought.isBlank() ? "Calling " + tool : thought, tool, args);
        } catch (Exception e) {
            log.error("Failed to parse Anthropic response: {}", e.getMessage());
            return AgentAction.finish("Could not parse model response; routing to review.",
                    "REVIEW", 50, "MEDIUM", List.of("Unparseable model response."));
        }
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }
}
