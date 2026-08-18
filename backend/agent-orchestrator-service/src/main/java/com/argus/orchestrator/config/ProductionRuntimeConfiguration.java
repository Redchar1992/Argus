package com.argus.orchestrator.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Prevents a production process from silently booting with demo-only runtime providers. */
@Component
public class ProductionRuntimeConfiguration {

    public ProductionRuntimeConfiguration(
            @Value("${argus.trace.store:memory}") String traceStore,
            @Value("${argus.llm.provider:local}") String llmProvider,
            @Value("${ARGUS_ANTHROPIC_API_KEY:}") String anthropicApiKey,
            Environment environment) {
        if (!isProduction(environment)) return;
        if (!"mongo".equalsIgnoreCase(traceStore)) {
            throw new IllegalStateException("Production requires the Mongo investigation trace store");
        }
        if (!"anthropic".equalsIgnoreCase(llmProvider)) {
            throw new IllegalStateException("Production requires an explicitly supported external LLM provider");
        }
        if (anthropicApiKey.isBlank()) {
            throw new IllegalStateException("ARGUS_ANTHROPIC_API_KEY is required in production");
        }
    }

    private static boolean isProduction(Environment environment) {
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile)) return true;
        }
        return false;
    }
}
