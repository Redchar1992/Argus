package com.storyforge.common.config;

import java.time.Duration;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.chapter-workflow")
public record ChapterWorkflowProperties(
        boolean redisEnabled,
        @NotBlank String commandStream,
        @NotBlank String eventStream,
        @NotBlank String eventGroup,
        @NotBlank String eventConsumer,
        @NotNull Duration pollTimeout,
        @Min(1) @Max(100) int batchSize,
        @NotNull Duration reclaimIdle,
        @Min(100) long reclaimIntervalMs,
        @Min(1) @Max(100) int reclaimBatchSize,
        @Min(100) long streamMaxLength,
        @Min(100) int eventRetentionPerTask,
        @Min(1000) long sseTimeoutMs
) { }
