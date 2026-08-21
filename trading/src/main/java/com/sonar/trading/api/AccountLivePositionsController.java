package com.sonar.trading.api;

import com.sonar.trading.config.AccountRegistry;
import com.sonar.trading.exchange.bingx.BingxFuturesClient;
import com.sonar.trading.reconciliation.BingxAccountParser;
import com.sonar.trading.reconciliation.model.ExchangePosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code GET /api/v1/accounts/{id}/live-positions} — `/positions` (5): PnL in the moment, straight
 * from the exchange's own response, not the DB snapshot. Same 5s per-account throttle as balance
 * (6) — a live REST call per position check, not a poll.
 */
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountLivePositionsController {

    private static final Logger log = LoggerFactory.getLogger(AccountLivePositionsController.class);
    private static final long THROTTLE_MS = 5_000;

    private final AccountRegistry accountRegistry;
    private final BingxFuturesClient bingxFuturesClient;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    public AccountLivePositionsController(AccountRegistry accountRegistry, BingxFuturesClient bingxFuturesClient) {
        this.accountRegistry = accountRegistry;
        this.bingxFuturesClient = bingxFuturesClient;
    }

    private record Cached(long fetchedAt, LivePositionsResponse value) {}

    public record LivePositionsResponse(String accountId, List<ExchangePosition> positions, String error) {}

    @GetMapping("/{id}/live-positions")
    public LivePositionsResponse positions(@PathVariable String id) {
        Cached cached = cache.get(id);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.fetchedAt() < THROTTLE_MS) {
            return cached.value();
        }
        LivePositionsResponse fresh = fetchLive(id);
        cache.put(id, new Cached(now, fresh));
        return fresh;
    }

    private LivePositionsResponse fetchLive(String id) {
        var account = accountRegistry.byId(id);
        if (account.isEmpty()) {
            return new LivePositionsResponse(id, List.of(), "аккаунт не найден");
        }
        if (!"bingx".equalsIgnoreCase(account.get().exchange())) {
            return new LivePositionsResponse(id, List.of(),
                    "venue '" + account.get().exchange() + "' не подключена в этой версии");
        }
        try {
            Map<String, Object> resp = bingxFuturesClient.getPositions(null);
            List<ExchangePosition> positions = BingxAccountParser.parsePositions(resp).stream()
                    .filter(p -> !p.isFlat())
                    .toList();
            return new LivePositionsResponse(id, positions, null);
        } catch (RuntimeException e) {
            log.error("[{}] live positions fetch failed: {}", id, e.toString());
            return new LivePositionsResponse(id, List.of(), e.getMessage());
        }
    }
}
