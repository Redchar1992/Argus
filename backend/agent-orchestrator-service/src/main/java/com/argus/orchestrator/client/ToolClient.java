package com.argus.orchestrator.client;

import com.argus.orchestrator.security.WorkloadTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.Map;

/**
 * Executes a tool by calling screening-tools-service over REST. The agent passes
 * the tool name + args map; we POST to /api/tools/{name} and return the parsed
 * JSON observation (as a generic Map so it round-trips into the trace).
 */
@Component
public class ToolClient {

    private static final Logger log = LoggerFactory.getLogger(ToolClient.class);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final WorkloadTokenService workloadTokens;

    public ToolClient(@Value("${argus.tools.base-url:http://localhost:8083}") String baseUrl,
                      ObjectMapper objectMapper,
                      WorkloadTokenService workloadTokens) {
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
        this.workloadTokens = workloadTokens;
    }

    /**
     * Invokes a screening tool with a fresh, audience-bound workload token. The actor is
     * an audit claim; the browser's user token is intentionally never propagated.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> invoke(String toolName, Map<String, Object> args, String actor) {
        try {
            String authorization = workloadTokens.authorizationFor(
                    WorkloadTokenService.TOOLS_AUDIENCE, actor);
            String body = webClient.post()
                    .uri("/api/tools/{tool}", toolName)
                    .header("Authorization", authorization)
                    .bodyValue(args == null ? Map.of() : args)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();
            return objectMapper.readValue(body, Map.class);
        } catch (WebClientResponseException e) {
            log.warn("Tool {} returned HTTP {}: {}", toolName, e.getStatusCode(), e.getMessage());
            return Map.of("error", "tool_http_error",
                    "status", e.getStatusCode().value(),
                    "tool", toolName);
        } catch (Exception e) {
            log.warn("Tool {} call failed: {}", toolName, e.getMessage());
            return Map.of("error", "tool_unreachable", "tool", toolName, "message",
                    String.valueOf(e.getMessage()));
        }
    }
}
