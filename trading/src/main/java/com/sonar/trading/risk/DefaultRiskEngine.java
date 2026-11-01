package com.sonar.trading.risk;

import com.sonar.trading.domain.entity.TradingStateEntity;
import com.sonar.trading.domain.repository.TradingStateRepository;
import com.sonar.trading.risk.rules.RiskRule;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Evaluates a proposal leg through all registered risk rules in priority order. Persists
 * auto-pause state so it survives restarts. No metrics registry (logs + Telegram only,
 * no Prometheus in v2) — breaches are logged and surfaced in the confirm/report flow instead.
 */
@Service
public class DefaultRiskEngine implements RiskEngine {

    private static final Logger log = LoggerFactory.getLogger(DefaultRiskEngine.class);
    private static final String AUTO_PAUSE_KEY_PREFIX = "auto_pause:";
    private static final String AUTO_PAUSE_CAUSE_KEY_PREFIX = "auto_pause_cause:";

    private final List<RiskRule> rules;
    private final TradingStateRepository stateRepository;

    private final Set<String> pausedAccounts = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, RiskDecision.PauseCause> causeByAccount = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LocalDate> pausedOnByAccount = new ConcurrentHashMap<>();

    public DefaultRiskEngine(List<RiskRule> rules, TradingStateRepository stateRepository) {
        this.rules = rules.stream().sorted(Comparator.comparingInt(RiskRule::priority)).toList();
        this.stateRepository = stateRepository;
    }

    @PostConstruct
    public void loadAutoPauseState() {
        for (TradingStateEntity state : stateRepository.findByKeyStartingWith(AUTO_PAUSE_KEY_PREFIX)) {
            if (state.getKey().startsWith(AUTO_PAUSE_CAUSE_KEY_PREFIX)) {
                continue;
            }
            if (Boolean.parseBoolean(state.getValue())) {
                String accountId = state.getKey().substring(AUTO_PAUSE_KEY_PREFIX.length());
                pausedAccounts.add(accountId);
                loadCause(accountId);
                log.warn("Auto-pause is ACTIVE for account {} (loaded from DB), cause={}",
                        accountId, causeByAccount.get(accountId));
            }
        }
        clearExpiredDailyPauses();
    }

    private void loadCause(String accountId) {
        stateRepository.findById(AUTO_PAUSE_CAUSE_KEY_PREFIX + accountId).ifPresent(meta -> {
            try {
                causeByAccount.put(accountId, RiskDecision.PauseCause.valueOf(meta.getValue()));
            } catch (IllegalArgumentException e) {
                log.warn("Unknown auto-pause cause '{}' for account {} — treating the cause as unknown",
                        meta.getValue(), accountId);
                return;
            }
            pausedOnByAccount.put(accountId,
                    Instant.ofEpochMilli(meta.getUpdatedAt()).atZone(ZoneOffset.UTC).toLocalDate());
        });
    }

    /**
     * Auto-clears DAILY_LOSS pauses once UTC-day rolls over — the poller's daily_baseline row
     * has already reset by then. MAX_DRAWDOWN pauses are never auto-cleared — a human clears
     * them after review.
     */
    @Scheduled(fixedDelayString = "${trading.auto-pause.rollover-check-ms:60000}")
    public void clearExpiredDailyPauses() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (String accountId : Set.copyOf(pausedAccounts)) {
            if (causeByAccount.get(accountId) != RiskDecision.PauseCause.DAILY_LOSS) {
                continue;
            }
            LocalDate pausedOn = pausedOnByAccount.get(accountId);
            if (pausedOn == null || !pausedOn.isBefore(today)) {
                continue;
            }
            log.warn("Auto-pause for account {} cleared automatically: the DAILY_LOSS cause belonged "
                    + "to {}, a new UTC day {} has started", accountId, pausedOn, today);
            clearAutoPause(accountId);
        }
    }

    @Override
    public RiskDecision evaluate(RiskContext context) {
        RiskDecision softDecision = null;
        for (RiskRule rule : rules) {
            Optional<RiskDecision> decision = rule.evaluate(context);
            if (decision.isEmpty()) {
                continue;
            }
            RiskDecision d = decision.get();
            log.info("Risk rule {} fired for account {}: outcome={} reason={}",
                    rule.getClass().getSimpleName(), context.accountId(), d.outcome(), d.reason());
            if (d.isBlocking()) {
                if (d.outcome() == RiskDecision.Outcome.PAUSE_TRADING) {
                    activateAutoPause(context.accountId(), d.pauseCause(), d.reason());
                }
                return d;
            }
            softDecision = mostConservative(softDecision, d);
        }
        return softDecision != null ? softDecision : RiskDecision.approve();
    }

    private static RiskDecision mostConservative(RiskDecision current, RiskDecision incoming) {
        if (current == null) {
            return incoming;
        }
        if (current.outcome() == RiskDecision.Outcome.REDUCE_SIZE
                && incoming.outcome() == RiskDecision.Outcome.REDUCE_SIZE
                && current.sizeMultiplier() != null && incoming.sizeMultiplier() != null) {
            return incoming.sizeMultiplier().compareTo(current.sizeMultiplier()) < 0 ? incoming : current;
        }
        return incoming.outcome() == RiskDecision.Outcome.CLOSE_ONLY ? incoming : current;
    }

    @Override
    @Transactional
    public void activateAutoPause(String accountId, RiskDecision.PauseCause cause, String reason) {
        log.warn("Activating auto-pause for account {} (cause={}): {}", accountId, cause, reason);
        pausedAccounts.add(accountId);
        if (cause != null) {
            causeByAccount.put(accountId, cause);
            pausedOnByAccount.put(accountId, LocalDate.now(ZoneOffset.UTC));
        }
        saveState(AUTO_PAUSE_KEY_PREFIX + accountId, "true");
        if (cause != null) {
            saveState(AUTO_PAUSE_CAUSE_KEY_PREFIX + accountId, cause.name());
        }
    }

    @Override
    @Transactional
    public void clearAutoPause(String accountId) {
        log.info("Clearing auto-pause for account {}", accountId);
        pausedAccounts.remove(accountId);
        causeByAccount.remove(accountId);
        pausedOnByAccount.remove(accountId);
        saveState(AUTO_PAUSE_KEY_PREFIX + accountId, "false");
        stateRepository.findById(AUTO_PAUSE_CAUSE_KEY_PREFIX + accountId)
                .ifPresent(stateRepository::delete);
    }

    private void saveState(String key, String value) {
        TradingStateEntity state = stateRepository.findById(key)
                .orElse(new TradingStateEntity(key, value));
        state.setValue(value);
        stateRepository.save(state);
    }

    @Override
    public boolean isAutoPauseActive(String accountId) {
        return pausedAccounts.contains(accountId);
    }

    @Override
    public RiskDecision.PauseCause autoPauseCause(String accountId) {
        return causeByAccount.get(accountId);
    }
}
