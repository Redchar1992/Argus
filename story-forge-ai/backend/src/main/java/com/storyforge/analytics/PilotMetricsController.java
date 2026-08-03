package com.storyforge.analytics;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import com.storyforge.common.exception.ApiException;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/internal/pilot")
public class PilotMetricsController {
    private final PilotMetricsService metrics;
    private final PilotMetricsProperties properties;

    public PilotMetricsController(
            PilotMetricsService metrics,
            PilotMetricsProperties properties
    ) {
        this.metrics = metrics;
        this.properties = properties;
    }

    @GetMapping("/metrics")
    public PilotMetricsResponse metrics(
            @RequestHeader(name = "X-Pilot-Metrics-Key", required = false) String key,
            @RequestParam(defaultValue = "7") @Min(1) @Max(90) int days
    ) {
        requireMetricsKey(key);
        return metrics.snapshot(days);
    }

    private void requireMetricsKey(String provided) {
        String expected = properties.metricsKey();
        if (!StringUtils.hasText(expected)) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "PILOT_METRICS_DISABLED",
                    "内测指标接口未启用"
            );
        }
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] providedBytes = provided == null
                ? new byte[0]
                : provided.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedBytes, providedBytes)) {
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "PILOT_METRICS_UNAUTHORIZED",
                    "内测指标访问密钥无效"
            );
        }
    }
}
