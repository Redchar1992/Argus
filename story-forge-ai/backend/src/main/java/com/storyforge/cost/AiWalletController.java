package com.storyforge.cost;

import java.util.List;

import com.storyforge.common.security.AuthenticatedUser;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me/ai-wallet")
public class AiWalletController {
    private final AiCreditService credits;

    public AiWalletController(AiCreditService credits) {
        this.credits = credits;
    }

    @GetMapping
    public AiWalletResponse wallet(@AuthenticationPrincipal AuthenticatedUser user) {
        return credits.get(user.userId());
    }

    @GetMapping("/logs")
    public List<AiCreditLogResponse> logs(@AuthenticationPrincipal AuthenticatedUser user) {
        return credits.logs(user.userId());
    }
}
