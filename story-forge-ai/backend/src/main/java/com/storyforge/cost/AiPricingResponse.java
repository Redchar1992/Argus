package com.storyforge.cost;

public record AiPricingResponse(
        String operationType,
        Long credits,
        Boolean enabled
) { }
