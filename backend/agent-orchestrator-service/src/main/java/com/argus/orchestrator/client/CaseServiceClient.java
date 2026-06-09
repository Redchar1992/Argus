package com.argus.orchestrator.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Mirrors a completed investigation's decision into case-service (the durable
 * SQL case header + audit trail). Best-effort: a case-service outage must not
 * lose the in-orchestrator trace, so failures are logged, not thrown.
 */
@Component
public class CaseServiceClient {

    private static final Logger log = LoggerFactory.getLogger(CaseServiceClient.class);

    private final WebClient webClient;
    private final ObjectMapper mapper;
    private final boolean enabled;

    public CaseServiceClient(
            @Value("${argus.case.base-url:http://localhost:8084}") String baseUrl,
            @Value("${argus.case.mirror-enabled:true}") boolean enabled,
            ObjectMapper mapper) {
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
        this.enabled = enabled;
        this.mapper = mapper;
    }

    public void mirrorCase(String id, String subjectAddress, String decision, int riskScore,
                           String riskBand, String summary, List<String> riskFactors,
                           String createdBy, String bearerToken) {
        if (!enabled) {
            return;
        }
        try {
            Map<String, Object> body = Map.of(
                    "id", id,
                    "subjectAddress", subjectAddress,
                    "decision", decision,
                    "riskScore", riskScore,
                    "riskBand", riskBand == null ? "" : riskBand,
                    "summary", summary == null ? "" : summary,
                    "riskFactorsJson", mapper.writeValueAsString(riskFactors),
                    "createdBy", createdBy == null ? "system" : createdBy);
            webClient.post()
                    .uri("/api/cases")
                    .headers(h -> {
                        if (bearerToken != null && !bearerToken.isBlank()) {
                            h.set("Authorization", bearerToken);
                        }
                    })
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(5))
                    .block();
        } catch (Exception e) {
            log.warn("Could not mirror case {} to case-service: {}", id, e.getMessage());
        }
    }
}
