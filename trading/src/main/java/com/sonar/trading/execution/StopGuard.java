package com.sonar.trading.execution;

import com.sonar.trading.exchange.dto.AdapterOpenOrder;
import com.sonar.trading.exchange.spi.ExecutionAdapter;
import com.sonar.trading.execution.adapter.ExecutionAdapterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * The 3rd invariant, enforced twice: right after an entry fills (4.6 step 5) and again every
 * minute for every open position (4.7) — a position must never sit without a reduce-only stop on
 * the exchange. One remediation attempt per check; a still-missing stop after that is the
 * poller's job to escalate loudly (Telegram, stage 6), not to retry silently forever.
 *
 * <p>Venue-agnostic (grilled Q5): resolves the right {@link ExecutionAdapter} per call via
 * {@link ExecutionAdapterRegistry} instead of talking to a BingX-specific client — one class
 * serves every wired venue (BingX, Bybit, ...).
 */
@Component
public class StopGuard {

    private static final Logger log = LoggerFactory.getLogger(StopGuard.class);

    private final ExecutionAdapterRegistry adapters;

    public StopGuard(ExecutionAdapterRegistry adapters) {
        this.adapters = adapters;
    }

    /**
     * One read of the venue's resting orders, answering both questions the poller has: is a stop
     * present, and at what levels do the stop/take-profit sit. They used to be two separate calls
     * against the same endpoint (once per position per tick each), which is what tripped BingX's
     * trigger-endpoint frequency limit and, through the bug below, produced false MISSING_STOP
     * alerts on positions that were protected the whole time.
     */
    public StopInspection inspect(String accountId, String coreSymbol) {
        ExecutionAdapter adapter = adapters.forAccount(accountId).orElse(null);
        if (adapter == null) {
            return StopInspection.unreadable();
        }
        try {
            BigDecimal sl = null;
            BigDecimal tp = null;
            boolean hasStop = false;
            for (AdapterOpenOrder o : adapter.listOpenOrders(coreSymbol)) {
                if (o.type() == null) {
                    continue;
                }
                String type = o.type().toUpperCase(java.util.Locale.ROOT);
                boolean isTakeProfit = type.contains("TAKE") || type.contains("PROFIT");
                boolean isStop = !isTakeProfit && type.contains("STOP");
                BigDecimal level = o.triggerPrice();
                if (isStop) {
                    hasStop = true;
                    if (level != null && level.signum() != 0) {
                        sl = level;
                    }
                } else if (isTakeProfit && level != null && level.signum() != 0) {
                    tp = level;
                }
            }
            return new StopInspection(true, hasStop, sl, tp);
        } catch (RuntimeException e) {
            // Could not ask — NOT the same as "there is no stop". Conflating the two is what made a
            // transient rate limit look like an unprotected position, which in turn drove the
            // remediation path towards placing a duplicate stop order on a real-money account.
            log.warn("[stop-check] {} {} could not read resting orders: {}", accountId, coreSymbol, e.toString());
            return StopInspection.unreadable();
        }
    }

    /**
     * What the venue told us about a symbol's protective orders. {@code readable=false} means the
     * question could not be asked at all (no adapter, API error, rate limit) — callers must treat
     * that as "unknown", never as "unprotected".
     */
    public record StopInspection(boolean readable, boolean hasStop, BigDecimal stopLoss, BigDecimal takeProfit) {
        public static StopInspection unreadable() {
            return new StopInspection(false, false, null, null);
        }
    }

    /**
     * True if a stop was already present, or the one remediation attempt placed one successfully.
     *
     * <p>{@code known} tells this method what the caller already learned from {@link #inspect}: a
     * position whose orders could not be read is left alone (no remediation, no alert), because
     * placing a "replacement" stop for one that may well exist is worse than the gap it pretends
     * to close.
     */
    public boolean verifyAndRepair(String accountId, String coreSymbol, BigDecimal qty, BigDecimal slPrice,
                                   StopInspection known) {
        if (!known.readable()) {
            return true; // unknown, not unprotected — say nothing rather than raise a false alarm
        }
        if (known.hasStop()) {
            return true;
        }
        ExecutionAdapter adapter = adapters.forAccount(accountId).orElse(null);
        if (adapter == null) {
            return false;
        }
        if (slPrice == null) {
            log.error("[STOP-MISSING] {}/{} has no stop on the venue and no SL level on record — "
                    + "cannot place a remediation order", accountId, coreSymbol);
            return false;
        }
        try {
            log.error("[STOP-MISSING] no conditional stop found for {}/{} — placing remediation order",
                    accountId, coreSymbol);
            adapter.placeReduceOnlyStop(coreSymbol, qty, slPrice);
            return inspect(accountId, coreSymbol).hasStop();
        } catch (RuntimeException e) {
            log.error("[STOP-MISSING] remediation failed for {}/{}: {}", accountId, coreSymbol, e.toString());
            return false;
        }
    }

    /** Read-only check, no remediation — used by the poller to decide whether to even call {@link #verifyAndRepair}. */
    public boolean hasStopOrder(String accountId, String coreSymbol) {
        return adapters.forAccount(accountId).map(a -> a.hasOpenStopOrder(coreSymbol)).orElse(false);
    }

    /**
     * False for a venue with no {@link ExecutionAdapter} wired at all (today: Pionex — and not
     * merely "not implemented yet": its Futures API, still Beta, exposes no TP/SL surface at all,
     * verified against Pionex's published OpenAPI specs — no field on the order schema, no
     * endpoint, no WebSocket topic; the feature exists only inside their web UI).
     *
     * <p>The poller uses this to skip the stop check <b>and its alert</b> entirely for such venues.
     * Everywhere the question can be answered, a missing stop stays a loud, every-tick
     * {@code MISSING_STOP} — SL/TP are mandatory. Silence here is not a relaxed rule, it is the
     * absence of any signal to raise it from.
     */
    public boolean isSupported(String accountId) {
        return adapters.forAccount(accountId).isPresent();
    }
}
