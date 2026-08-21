package com.sonar.trading.health;

import com.sonar.trading.config.TradingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks local system health (ADR-0008):
 * - market data staleness per symbol
 * - consumer lag
 * - reconciliation sync flag
 */
@Service
public class SystemHealthService {

    private static final Logger log = LoggerFactory.getLogger(SystemHealthService.class);

    private final TradingProperties props;

    /** Fallback account id for the single-account / legacy path (matches the DB backfill). */
    public static final String DEFAULT_ACCOUNT = "default";

    private final ConcurrentHashMap<String, Long> lastMarketEventTimeBySymbol = new ConcurrentHashMap<>();
    private final AtomicLong consumerLag = new AtomicLong(0);
    private volatile boolean reconciliationInSync = true;
    /** Execution circuit state per account (P3): one account's open circuit must not gate the others. */
    private final ConcurrentHashMap<String, Boolean> circuitOpenByAccount = new ConcurrentHashMap<>();
    /**
     * {@code (accountId, symbol)} pairs currently in a reconciliation safe-window (mismatch
     * detected, not yet re-synced). New entries on that account+symbol are blocked (T17). Keyed
     * per account since the multi-account epic: one account's divergence must not gate the same
     * symbol on healthy accounts.
     */
    private final Set<String> outOfSyncAccountSymbols = ConcurrentHashMap.newKeySet();

    public SystemHealthService(TradingProperties props) {
        this.props = props;
    }

    public void recordMarketEvent(String symbol, long exchangeEventTime) {
        // merge/max, не put: отставшее событие (реплей, поздний бэкфилл) не должно ОТКАТЫВАТЬ
        // свежесть назад и рубить валидные входы (ревью #86).
        lastMarketEventTimeBySymbol.merge(symbol.toUpperCase(), exchangeEventTime, Math::max);
    }

    public void setConsumerLag(long lag) {
        this.consumerLag.set(lag);
    }

    public void setReconciliationInSync(boolean inSync) {
        this.reconciliationInSync = inSync;
    }

    /**
     * Set by an account's Execution Gateway (T16) when its circuit breaker opens/closes. An open
     * circuit degrades that account's health so the risk gate blocks its new entries (closes stay
     * exempt there). Per-account (P3): each account's circuit is independent.
     */
    public void setExecutionCircuitOpen(String accountId, boolean open) {
        circuitOpenByAccount.put(accountId == null ? DEFAULT_ACCOUNT : accountId, open);
    }

    public boolean isExecutionCircuitOpen(String accountId) {
        return circuitOpenByAccount.getOrDefault(accountId == null ? DEFAULT_ACCOUNT : accountId, false);
    }

    /** Legacy single-account setter — targets the {@link #DEFAULT_ACCOUNT} circuit. */
    public void setExecutionCircuitOpen(boolean open) {
        setExecutionCircuitOpen(DEFAULT_ACCOUNT, open);
    }

    /** Legacy single-account read — the {@link #DEFAULT_ACCOUNT} circuit. */
    public boolean isExecutionCircuitOpen() {
        return isExecutionCircuitOpen(DEFAULT_ACCOUNT);
    }

    /** Accounts whose execution circuit has reported state at least once (immutable copy, safe to iterate). */
    public Set<String> circuitAccounts() {
        return Set.copyOf(circuitOpenByAccount.keySet());
    }

    /**
     * Put an account's symbol into a reconciliation safe-window (T17): a mismatch was detected and
     * local state is being pulled in line with the exchange. New entries on this account+symbol
     * are blocked until cleared; the same symbol on other accounts stays unaffected.
     */
    public void markSymbolOutOfSync(String accountId, String symbol) {
        outOfSyncAccountSymbols.add(accountSymbolKey(accountId, symbol));
    }

    /** Lift the per-account-symbol reconciliation block after local state matches the exchange again. */
    public void clearSymbolOutOfSync(String accountId, String symbol) {
        outOfSyncAccountSymbols.remove(accountSymbolKey(accountId, symbol));
    }

    public boolean isSymbolOutOfSync(String accountId, String symbol) {
        return outOfSyncAccountSymbols.contains(accountSymbolKey(accountId, symbol));
    }

    /** Legacy single-account mark — targets the {@link #DEFAULT_ACCOUNT} dimension. */
    public void markSymbolOutOfSync(String symbol) {
        markSymbolOutOfSync(DEFAULT_ACCOUNT, symbol);
    }

    /** Legacy single-account clear — targets the {@link #DEFAULT_ACCOUNT} dimension. */
    public void clearSymbolOutOfSync(String symbol) {
        clearSymbolOutOfSync(DEFAULT_ACCOUNT, symbol);
    }

    public boolean isSymbolOutOfSync(String symbol) {
        return isSymbolOutOfSync(DEFAULT_ACCOUNT, symbol);
    }

    private static String accountSymbolKey(String accountId, String symbol) {
        return (accountId == null ? DEFAULT_ACCOUNT : accountId) + "|" + symbol.toUpperCase();
    }

    public boolean isHealthy(String symbol) {
        return isHealthy(symbol, DEFAULT_ACCOUNT);
    }

    public boolean isHealthy(String symbol, String accountId) {
        return getStalenessMs(symbol) <= props.getHealth().getMaxStalenessMs()
                && consumerLag.get() <= props.getHealth().getMaxConsumerLag()
                && reconciliationInSync
                && !isSymbolOutOfSync(accountId, symbol)
                && !isExecutionCircuitOpen(accountId);
    }

    public long getStalenessMs(String symbol) {
        Long lastTime = lastMarketEventTimeBySymbol.get(symbol.toUpperCase());
        if (lastTime == null) {
            return Long.MAX_VALUE;
        }
        return System.currentTimeMillis() - lastTime;
    }

    public long getConsumerLag() {
        return consumerLag.get();
    }

    /** Symbols that have received at least one market event (immutable copy, safe to iterate). */
    public Set<String> trackedSymbols() {
        return Set.copyOf(lastMarketEventTimeBySymbol.keySet());
    }

    /** Number of (account, symbol) pairs currently in a reconciliation safe-window (T17). */
    public int outOfSyncCount() {
        return outOfSyncAccountSymbols.size();
    }

    public boolean isReconciliationInSync() {
        return reconciliationInSync;
    }

    public HealthSnapshot snapshot(String symbol) {
        return snapshot(symbol, DEFAULT_ACCOUNT);
    }

    public HealthSnapshot snapshot(String symbol, String accountId) {
        long staleness = getStalenessMs(symbol);
        long lag = consumerLag.get();
        boolean symbolInSync = !isSymbolOutOfSync(accountId, symbol);
        boolean healthy = staleness <= props.getHealth().getMaxStalenessMs()
                && lag <= props.getHealth().getMaxConsumerLag()
                && reconciliationInSync
                && symbolInSync
                && !isExecutionCircuitOpen(accountId);
        return new HealthSnapshot(healthy, staleness, lag, reconciliationInSync && symbolInSync);
    }

    public record HealthSnapshot(boolean healthy, long stalenessMs, long consumerLag, boolean reconciliationInSync) {}
}
