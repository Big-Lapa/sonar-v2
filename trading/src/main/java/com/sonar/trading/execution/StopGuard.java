package com.sonar.trading.execution;

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

    /** True if a stop was already present, or the one remediation attempt placed one successfully. */
    public boolean verifyAndRepair(String exchange, String coreSymbol, BigDecimal qty, BigDecimal slPrice) {
        ExecutionAdapter adapter = adapters.forExchange(exchange).orElse(null);
        if (adapter == null) {
            log.error("[STOP-MISSING] no execution adapter wired for venue '{}' — cannot verify stop for {}",
                    exchange, coreSymbol);
            return false;
        }
        try {
            if (adapter.hasOpenStopOrder(coreSymbol)) {
                return true;
            }
            log.error("[STOP-MISSING] no conditional stop found for {}/{} — placing remediation order",
                    exchange, coreSymbol);
            adapter.placeReduceOnlyStop(coreSymbol, qty, slPrice);
            return adapter.hasOpenStopOrder(coreSymbol);
        } catch (RuntimeException e) {
            log.error("[STOP-MISSING] remediation failed for {}/{}: {}", exchange, coreSymbol, e.toString());
            return false;
        }
    }

    /** Read-only check, no remediation — used by the poller to decide whether to even call {@link #verifyAndRepair}. */
    public boolean hasStopOrder(String exchange, String coreSymbol) {
        return adapters.forExchange(exchange).map(a -> a.hasOpenStopOrder(coreSymbol)).orElse(false);
    }
}
