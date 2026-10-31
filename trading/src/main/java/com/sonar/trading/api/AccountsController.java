package com.sonar.trading.api;

import com.sonar.trading.config.AccountRegistry;
import com.sonar.trading.domain.repository.AccountRiskRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 4.8/6: {@code GET /api/v1/accounts} — registry without secrets, for the admin "Positions" accordion. */
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountsController {

    private final AccountRegistry accountRegistry;
    private final AccountRiskRepository accountRiskRepository;

    public AccountsController(AccountRegistry accountRegistry, AccountRiskRepository accountRiskRepository) {
        this.accountRegistry = accountRegistry;
        this.accountRiskRepository = accountRiskRepository;
    }

    public record AccountSummary(String id, String exchange, boolean tradable) {}

    @GetMapping
    public List<AccountSummary> list() {
        return accountRegistry.all().stream()
                .map(a -> new AccountSummary(a.id(), a.exchange(),
                        accountRiskRepository.findById(a.id()).map(r -> r.isTradable()).orElse(false)))
                .toList();
    }
}
