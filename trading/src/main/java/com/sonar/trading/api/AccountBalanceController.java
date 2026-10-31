package com.sonar.trading.api;

import com.sonar.trading.config.AccountRegistry;
import com.sonar.trading.reconciliation.AccountReaderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 6 (admin "Positions" block): {@code GET /api/v1/accounts/{id}/balance} — live REST to the
 * exchange, not the daily_baseline snapshot. Throttled 5s per account server-side regardless of
 * the client's ↻ button, so a stuck/fast-clicking admin tab can't hammer the exchange.
 *
 * <p>Venue-agnostic: resolves the account's {@code AccountReader} by venue instead of a
 * hardcoded BingX client.
 */
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountBalanceController {

    private static final Logger log = LoggerFactory.getLogger(AccountBalanceController.class);
    private static final long THROTTLE_MS = 5_000;

    private final AccountRegistry accountRegistry;
    private final AccountReaderRegistry accountReaders;
    private final ConcurrentHashMap<String, CachedBalance> cache = new ConcurrentHashMap<>();

    public AccountBalanceController(AccountRegistry accountRegistry, AccountReaderRegistry accountReaders) {
        this.accountRegistry = accountRegistry;
        this.accountReaders = accountReaders;
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
            return new BalanceResponse(id, null, null, null, null, System.currentTimeMillis(), true, "account not found");
        }
        var reader = accountReaders.forAccount(id);
        if (reader.isEmpty()) {
            return new BalanceResponse(id, null, null, null, null, System.currentTimeMillis(), true,
                    account.get().isConfigured()
                            ? "venue '" + account.get().exchange() + "' is not connected in this version"
                            : "account has no API keys configured");
        }
        try {
            var balance = reader.get().getBalance().orElse(null);
            if (balance == null) {
                return new BalanceResponse(id, null, null, null, null, System.currentTimeMillis(), true, "empty exchange response");
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
