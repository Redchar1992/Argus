package com.storyforge.prompt;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record ModelProfileRequest(
        @NotBlank @Size(max = 64) String profileKey,
        @NotBlank @Size(max = 50) String provider,
        @NotBlank @Size(max = 100) String modelName,
        @DecimalMin("0.0") @DecimalMax("2.0") BigDecimal temperature,
        @Min(1) @Max(100000) Integer maxTokens,
        @Size(max = 255) String secretReference
) { }
