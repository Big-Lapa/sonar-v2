package com.sonar.trading.api;

import com.sonar.trading.risk.RiskEngine;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 4.8: {@code POST /api/v1/accounts/{id}/auto-pause/clear} — `/unpause` (5). */
@RestController
@RequestMapping("/api/v1/accounts")
public class AutoPauseController {

    private final RiskEngine riskEngine;

    public AutoPauseController(RiskEngine riskEngine) {
        this.riskEngine = riskEngine;
    }

    @PostMapping("/{id}/auto-pause/clear")
    public Map<String, Object> clear(@PathVariable String id) {
        boolean wasPaused = riskEngine.isAutoPauseActive(id);
        riskEngine.clearAutoPause(id);
        return Map.of("accountId", id, "wasPaused", wasPaused, "autoPaused", false);
    }
}
