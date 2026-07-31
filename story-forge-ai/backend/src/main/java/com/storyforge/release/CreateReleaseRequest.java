package com.storyforge.release;

import jakarta.validation.constraints.Positive;

public record CreateReleaseRequest(@Positive Long reportId) { }
