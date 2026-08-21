package com.sonar.trading.api;

import com.sonar.trading.domain.entity.AccountRiskEntity;
import com.sonar.trading.domain.repository.AccountRiskRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Per-account risk profile CRUD (4.1/4.2/6 — admin "Риск" block, PUT with optimistic lock). */
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountRiskController {

    private final AccountRiskRepository repository;

    public AccountRiskController(AccountRiskRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/{id}/risk")
    public AccountRiskEntity get(@PathVariable String id) {
        return repository.findById(id).orElseGet(() -> repository.save(AccountRiskEntity.defaultsFor(id)));
    }

    public record RiskUpdateRequest(
            String riskPctPerTrade,
            String maxPortfolioRiskPct,
            String maxPositionNotional,
            String maxDailyRealizedLossPct,
            String maxEquityDrawdownPct,
            Integer maxOpenPositions,
            Integer maxLeverage,
            Boolean tradable,
            int version
    ) {}

    @PutMapping("/{id}/risk")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody RiskUpdateRequest req) {
        AccountRiskEntity entity = repository.findById(id).orElseGet(() -> AccountRiskEntity.defaultsFor(id));
        if (entity.getVersion() != req.version()) {
            throw new OptimisticLockingFailureException(
                    "risk profile for " + id + " changed since read (expected version " + req.version()
                            + ", actual " + entity.getVersion() + ")");
        }
        if (req.riskPctPerTrade() != null) entity.setRiskPctPerTrade(req.riskPctPerTrade());
        if (req.maxPortfolioRiskPct() != null) entity.setMaxPortfolioRiskPct(req.maxPortfolioRiskPct());
        if (req.maxPositionNotional() != null) entity.setMaxPositionNotional(req.maxPositionNotional());
        if (req.maxDailyRealizedLossPct() != null) entity.setMaxDailyRealizedLossPct(req.maxDailyRealizedLossPct());
        if (req.maxEquityDrawdownPct() != null) entity.setMaxEquityDrawdownPct(req.maxEquityDrawdownPct());
        if (req.maxOpenPositions() != null) entity.setMaxOpenPositions(req.maxOpenPositions());
        if (req.maxLeverage() != null) entity.setMaxLeverage(req.maxLeverage());
        if (req.tradable() != null) entity.setTradable(req.tradable());
        entity.setUpdatedAt(System.currentTimeMillis());
        return ResponseEntity.ok(repository.save(entity));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<String> onConflict(OptimisticLockingFailureException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    }
}
