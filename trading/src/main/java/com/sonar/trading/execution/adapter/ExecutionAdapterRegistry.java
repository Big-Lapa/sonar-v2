package com.sonar.trading.execution.adapter;

import com.sonar.trading.exchange.spi.ExecutionAdapter;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves the {@link ExecutionAdapter} for one account (full multi-venue routing — grilled plan).
 *
 * <p>Keyed by <b>account id</b>, not venue: two accounts on the same exchange have different
 * credentials and can sit on different domains (BingX VST demo vs production), so a venue-keyed
 * lookup could route a real-money order through a demo client or vice versa. Built by
 * {@code AccountWiring}; PAPER mode (no adapters wired at all) yields an empty registry rather
 * than a startup error, same "LIVE-only" posture as the gateways.
 */
public class ExecutionAdapterRegistry {

    private final Map<String, ExecutionAdapter> byAccountId;

    public ExecutionAdapterRegistry(Map<String, ExecutionAdapter> byAccountId) {
        this.byAccountId = Map.copyOf(byAccountId);
    }

    /** Empty when the account has no credentials, or its venue has no execution adapter (Pionex). */
    public Optional<ExecutionAdapter> forAccount(String accountId) {
        if (accountId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byAccountId.get(accountId.toLowerCase(Locale.ROOT)));
    }

    /**
     * Any wired adapter, deterministic-ish but account-unspecified — for checks that are venue
     * -indifferent (e.g. a coarse cross-venue price-drift sanity gate before an account/venue is
     * even known yet, see {@code ProposalExecutionService.checkPriceDrift}). Never use this for
     * anything that mutates state.
     */
    public Optional<ExecutionAdapter> any() {
        return byAccountId.values().stream().findFirst();
    }
}
