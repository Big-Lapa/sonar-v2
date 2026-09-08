package com.sonar.trading.config;

import jakarta.annotation.PostConstruct;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads the account registry out of {@code TRADING_ACCOUNTS_N_ID}/{@code TRADING_ACCOUNTS_N_EXCHANGE}
 * environment variables (11: exchange keys never touch the DB). Spring's relaxed binding maps the
 * env vars to {@code trading.accounts[N].id}/{@code .exchange}, so we read them straight from
 * {@link Environment} rather than requiring a YAML list.
 */
@Component
public class AccountRegistry {

    private final Environment env;
    private final List<AccountProps> accounts = new ArrayList<>();

    public AccountRegistry(Environment env) {
        this.env = env;
    }

    /**
     * Indices must be contiguous from 0 — the loop stops at the first gap, so a hole in the
     * numbering silently truncates the registry: every account after it disappears along with its
     * balance, positions and monitoring. Credentials live per account alongside the id
     * ({@code TRADING_ACCOUNTS_N_API_KEY} / {@code _API_SECRET} / {@code _BASE_URL}), never as a
     * venue-wide default — see {@link AccountProps}.
     */
    @PostConstruct
    void load() {
        for (int i = 0; ; i++) {
            String id = env.getProperty("trading.accounts[" + i + "].id");
            if (id == null || id.isBlank()) {
                break;
            }
            accounts.add(new AccountProps(
                    id,
                    env.getProperty("trading.accounts[" + i + "].exchange"),
                    env.getProperty("trading.accounts[" + i + "].api-key"),
                    env.getProperty("trading.accounts[" + i + "].api-secret"),
                    env.getProperty("trading.accounts[" + i + "].base-url")));
        }
    }

    public List<AccountProps> all() {
        return List.copyOf(accounts);
    }

    public boolean isEmpty() {
        return accounts.isEmpty();
    }

    public Optional<AccountProps> byId(String id) {
        return accounts.stream().filter(a -> a.id().equals(id)).findFirst();
    }
}
