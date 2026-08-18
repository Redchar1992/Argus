package com.argus.orchestrator.client;

import com.argus.orchestrator.agent.DecisionPolicy;
import com.argus.orchestrator.security.WorkloadTokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * Reads the admin-editable decision thresholds from case-service's {@code /api/policies}
 * so the agent's BLOCK/REVIEW bands track what the admin console publishes. This is what
 * makes the policy NON-decorative: change the policy, change agent behaviour.
 *
 * Best-effort: if the policy service is unreachable we fall back to conservative defaults
 * rather than failing the investigation.
 */
@Component
public class PolicyClient {

    private static final Logger log = LoggerFactory.getLogger(PolicyClient.class);

    private final WebClient webClient;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final WorkloadTokenService workloadTokens;

    public PolicyClient(
            @Value("${argus.case.base-url:http://localhost:8084}") String baseUrl,
            @Value("${argus.policy.enabled:true}") boolean enabled,
            ObjectMapper mapper,
            WorkloadTokenService workloadTokens) {
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
        this.enabled = enabled;
        this.mapper = mapper;
        this.workloadTokens = workloadTokens;
    }

    /** Fetches current decision bands with the orchestrator workload identity. */
    public DecisionPolicy fetchDecisionPolicy(String actor) {
        if (!enabled) {
            return DecisionPolicy.defaults();
        }
        try {
            String body = webClient.get()
                    .uri("/api/policies")
                    .header("Authorization", workloadTokens.authorizationFor(
                            WorkloadTokenService.CASES_AUDIENCE, actor))
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(5))
                    .block();
            JsonNode arr = mapper.readTree(body);
            DecisionPolicy defaults = DecisionPolicy.defaults();
            int block = valueOf(arr, "blockThreshold", defaults.blockThreshold());
            int review = valueOf(arr, "reviewThreshold", defaults.reviewThreshold());
            return new DecisionPolicy(block, review);
        } catch (Exception e) {
            log.warn("Could not load decision policy from case-service, using defaults: {}",
                    e.getMessage());
            return DecisionPolicy.defaults();
        }
    }

    private int valueOf(JsonNode policies, String key, int fallback) {
        if (policies != null && policies.isArray()) {
            for (JsonNode p : policies) {
                if (key.equals(p.path("key").asText())) {
                    return p.path("value").asInt(fallback);
                }
            }
        }
        return fallback;
    }
}
