package com.storyforge.chapter.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record PlanChapterRequest(@Min(800) @Max(5000) Integer targetLength) {
    public int normalizedTargetLength() { return targetLength == null ? 1600 : targetLength; }
}
