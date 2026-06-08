package com.argus.cases.config;

import com.argus.cases.model.ScreeningPolicy;
import com.argus.cases.repository.ScreeningPolicyRepository;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Seeds default decision thresholds. These map a final risk score onto a decision:
 *   score >= blockThreshold  -> BLOCK
 *   score >= reviewThreshold -> REVIEW
 *   otherwise                -> CLEAR
 * maxAgentSteps caps the agent loop length (defence against runaway investigations).
 */
@Configuration
public class PolicySeeder {

    @Bean
    public ApplicationRunner seedPolicies(ScreeningPolicyRepository repository) {
        return args -> {
            if (repository.count() > 0) {
                return;
            }
            repository.saveAll(List.of(
                    new ScreeningPolicy("blockThreshold",
                            "Risk score at/above which a wallet is BLOCKED", 60),
                    new ScreeningPolicy("reviewThreshold",
                            "Risk score at/above which a wallet is sent to manual REVIEW", 30),
                    new ScreeningPolicy("maxAgentSteps",
                            "Maximum plan-act-observe iterations per investigation", 8),
                    new ScreeningPolicy("traceMaxHops",
                            "Default hop depth for the trace_transactions tool", 3)
            ));
        };
    }
}
