package com.sonar.trading.execution;

import com.sonar.trading.exchange.dto.StopInspection;
import com.sonar.trading.exchange.spi.ExecutionAdapter;
import com.sonar.trading.execution.adapter.ExecutionAdapterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * The 3rd invariant, enforced twice: right after an entry fills and again every
 * minute for every open position — a position must never sit without a reduce-only stop on
 * the exchange. One remediation attempt per check; a still-missing stop after that is the
 * poller's job to escalate loudly (Telegram), not to retry silently forever.
 *
 * <p>Venue-agnostic (grilled Q5): resolves the right {@link ExecutionAdapter} per call via
 * {@link ExecutionAdapterRegistry} instead of talking to a BingX-specific client. How a stop is
 * actually recognised is <b>not</b> venue-agnostic (BingX: a separate conditional order; Bybit: a
 * position-level field), so that classification lives in each adapter's
 * {@link ExecutionAdapter#inspectStops} — this class only adds the readable/unreadable
 * distinction on top and decides what to do with the answer.
 */
@Component
public class StopGuard {

    private static final Logger log = LoggerFactory.getLogger(StopGuard.class);

    private final ExecutionAdapterRegistry adapters;

    public StopGuard(ExecutionAdapterRegistry adapters) {
        this.adapters = adapters;
    }

    /**
     * One read of the venue's own record of this symbol's stop/take-profit, answering both
     * questions the poller has: is a stop present, and at what levels. Delegates the actual
     * classification to the account's adapter — see the class javadoc for why that can't live
     * here — and adds the one thing every venue needs: a failed read must never look like "no
     * stop", which is what drove remediation to place a duplicate stop on a real-money account
     * during a transient rate limit.
     */
    public Check inspect(String accountId, String coreSymbol) {
        ExecutionAdapter adapter = adapters.forAccount(accountId).orElse(null);
        if (adapter == null) {
            return Check.unreadable();
        }
        try {
            StopInspection s = adapter.inspectStops(coreSymbol);
            return new Check(true, s.hasStop(), s.stopLoss(), s.takeProfit());
        } catch (RuntimeException e) {
            // Could not ask — NOT the same as "there is no stop". Conflating the two is what made a
            // transient rate limit look like an unprotected position, which in turn drove the
            // remediation path towards placing a duplicate stop order on a real-money account.
            log.warn("[stop-check] {} {} could not read resting orders: {}", accountId, coreSymbol, e.toString());
            return Check.unreadable();
        }
    }

    /**
     * What is known about a symbol's protective orders after one {@link #inspect} call.
     * {@code readable=false} means the question could not be asked at all (no adapter, API error,
     * rate limit) — callers must treat that as "unknown", never as "unprotected".
     */
    public record Check(boolean readable, boolean hasStop, BigDecimal stopLoss, BigDecimal takeProfit) {
        public static Check unreadable() {
            return new Check(false, false, null, null);
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
                                   Check known) {
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

    /**
     * Read-only check, no remediation — used where a full {@link #inspect} isn't already in hand
     * (limit-fill reconciliation, add-on entries). Delegates to {@link #inspect} so a failed venue
     * call or an unsupported venue reads as "don't alarm" rather than "no stop": the earlier
     * version called {@code adapter.hasOpenStopOrder} directly and folded both "no adapter" and any
     * exception straight into {@code false}, which is the same "unknown looks unprotected" bug
     * {@link #verifyAndRepair} was fixed for — just reachable from a different caller
     * ({@code PendingLimitOrderReconciler}, {@code ProposalExecutionService}'s add-on branches).
     */
    public boolean hasStopOrder(String accountId, String coreSymbol) {
        Check check = inspect(accountId, coreSymbol);
        return !check.readable() || check.hasStop();
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
