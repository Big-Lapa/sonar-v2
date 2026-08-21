package com.sonar.trading.reconciliation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registry {@code accountId → AccountReconciler} (P4). Populated during singleton initialization:
 * the adapter factory registers one reconciler per LIVE (BINGX/BINANCE) account, and the legacy
 * single-account LIVE fallback registers exactly one (see {@link ReconciliationConfig}). Empty in
 * PAPER-only setups — the reconciliation worker then stays idle and never blocks entries.
 */
@Component
public class AccountReconcilers {

    private static final Logger log = LoggerFactory.getLogger(AccountReconcilers.class);

    private final Map<String, AccountReconciler> byAccountId = Collections.synchronizedMap(new LinkedHashMap<>());

    public void register(AccountReconciler reconciler) {
        AccountReconciler prev = byAccountId.put(reconciler.accountId(), reconciler);
        if (prev != null) {
            throw new IllegalStateException("Duplicate AccountReconciler for account '" + reconciler.accountId() + "'");
        }
        log.info("AccountReconcilers: registered {} reconciler for account '{}'",
                reconciler.exchange(), reconciler.accountId());
    }

    public boolean isEmpty() {
        return byAccountId.isEmpty();
    }

    /** Registered reconcilers in registration order (unmodifiable). */
    public Map<String, AccountReconciler> all() {
        return Collections.unmodifiableMap(byAccountId);
    }
}
