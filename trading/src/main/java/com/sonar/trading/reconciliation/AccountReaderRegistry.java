package com.sonar.trading.reconciliation;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves the {@link AccountReader} for one account — mirrors {@code ExecutionAdapterRegistry}.
 *
 * <p>Keyed by <b>account id</b>, not venue: the same exchange can host several accounts with
 * different credentials and even different domains (BingX VST demo vs production), so a
 * venue-keyed lookup would silently hand one account's client to another. Built by
 * {@code AccountWiring}, one client stack per configured account.
 */
public class AccountReaderRegistry {

    private final Map<String, AccountReader> byAccountId;

    public AccountReaderRegistry(Map<String, AccountReader> byAccountId) {
        this.byAccountId = Map.copyOf(byAccountId);
    }

    /** Empty when the account has no credentials configured, or its venue has no reader wired. */
    public Optional<AccountReader> forAccount(String accountId) {
        if (accountId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byAccountId.get(accountId.toLowerCase(Locale.ROOT)));
    }
}
