package com.sonar.trading.reconciliation;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Resolves the {@link AccountReader} for an account's venue — mirrors {@code ExecutionAdapterRegistry}. */
@Component
public class AccountReaderRegistry {

    private final Map<String, AccountReader> byExchange = new ConcurrentHashMap<>();

    public AccountReaderRegistry(List<AccountReader> readers) {
        for (AccountReader reader : readers) {
            byExchange.put(reader.exchange().toUpperCase(Locale.ROOT), reader);
        }
    }

    public Optional<AccountReader> forExchange(String exchange) {
        if (exchange == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byExchange.get(exchange.toUpperCase(Locale.ROOT)));
    }
}
