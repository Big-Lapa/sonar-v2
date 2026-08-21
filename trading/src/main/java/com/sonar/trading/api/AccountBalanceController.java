package com.sonar.trading.api;

import com.sonar.trading.config.AccountRegistry;
import com.sonar.trading.exchange.bingx.BingxFuturesClient;
import com.sonar.trading.reconciliation.BingxAccountParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 6 (admin "Позиции" block): {@code GET /api/v1/accounts/{id}/balance} — live REST to the
 * exchange, not the daily_baseline snapshot. Throttled 5s per account server-side regardless of
 * the client's ↻ button, so a stuck/fast-clicking admin tab can't hammer the exchange.
 */
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountBalanceController {

    private static final Logger log = LoggerFactory.getLogger(AccountBalanceController.class);
    private static final long THROTTLE_MS = 5_000;

    private final AccountRegistry accountRegistry;
    private final BingxFuturesClient bingxFuturesClient;
    private final ConcurrentHashMap<String, CachedBalance> cache = new ConcurrentHashMap<>();

    public AccountBalanceController(AccountRegistry accountRegistry, BingxFuturesClient bingxFuturesClient) {
        this.accountRegistry = accountRegistry;
        this.bingxFuturesClient = bingxFuturesClient;
    }

    private record CachedBalance(long fetchedAt, BalanceResponse value) {}

    public record BalanceResponse(String accountId, String asset, String balance, String equity,
                                  String unrealizedPnl, long fetchedAt, boolean stale, String error) {}

    @GetMapping("/{id}/balance")
    public BalanceResponse balance(@PathVariable String id, @RequestParam(defaultValue = "false") boolean refresh) {
        CachedBalance cached = cache.get(id);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.fetchedAt() < THROTTLE_MS) {
            return cached.value();
        }
        BalanceResponse fresh = fetchLive(id);
        cache.put(id, new CachedBalance(now, fresh));
        return fresh;
    }

    private BalanceResponse fetchLive(String id) {
        var account = accountRegistry.byId(id);
        if (account.isEmpty()) {
            return new BalanceResponse(id, null, null, null, null, System.currentTimeMillis(), true, "аккаунт не найден");
        }
        if (!"bingx".equalsIgnoreCase(account.get().exchange())) {
            return new BalanceResponse(id, null, null, null, null, System.currentTimeMillis(), true,
                    "venue '" + account.get().exchange() + "' не подключена в этой версии");
        }
        try {
            Map<String, Object> resp = bingxFuturesClient.getBalance();
            var balance = BingxAccountParser.parseBalance(resp, "VST").orElse(null);
            if (balance == null) {
                return new BalanceResponse(id, null, null, null, null, System.currentTimeMillis(), true, "пустой ответ биржи");
            }
            return new BalanceResponse(id, balance.asset(),
                    balance.balance() != null ? balance.balance().toPlainString() : null,
                    balance.equity() != null ? balance.equity().toPlainString() : null,
                    balance.unrealizedPnl() != null ? balance.unrealizedPnl().toPlainString() : null,
                    System.currentTimeMillis(), false, null);
        } catch (RuntimeException e) {
            log.error("[{}] live balance fetch failed: {}", id, e.toString());
            return new BalanceResponse(id, null, null, null, null, System.currentTimeMillis(), true, e.getMessage());
        }
    }
}
