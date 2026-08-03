package com.storyforge.analytics;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.pilot")
public record PilotMetricsProperties(String metricsKey) {
}
