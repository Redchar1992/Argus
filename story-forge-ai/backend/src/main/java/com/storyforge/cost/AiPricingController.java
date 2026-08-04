package com.storyforge.cost;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public read-only pricing for the authenticated product UI. */
@RestController
@RequestMapping("/api/ai/pricing")
public class AiPricingController {
    private final AiPricingService pricing;

    public AiPricingController(AiPricingService pricing) {
        this.pricing = pricing;
    }

    @GetMapping
    public List<AiPricingResponse> list() {
        return pricing.list();
    }
}
