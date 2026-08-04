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
    private final AiQuotaService quota;

    public AiWalletController(AiCreditService credits, AiQuotaService quota) {
        this.credits = credits;
        this.quota = quota;
    }

    @GetMapping
    public AiWalletView wallet(@AuthenticationPrincipal AuthenticatedUser user) {
        AiWalletResponse wallet = credits.get(user.userId());
        AiQuotaService.QuotaSnapshot snapshot = quota.snapshot(user.userId());
        return new AiWalletView(wallet.userId(), wallet.availableCredits(), wallet.frozenCredits(),
                wallet.consumedCredits(), wallet.updatedTime(), snapshot.planCode(), snapshot.dailyLimit(),
                snapshot.monthlyLimit(), snapshot.dailyRemaining(), snapshot.monthlyRemaining(),
                snapshot.maxConcurrentTasks());
    }

    @GetMapping("/logs")
    public List<AiCreditLogResponse> logs(@AuthenticationPrincipal AuthenticatedUser user) {
        return credits.logs(user.userId());
    }
}
