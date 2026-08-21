package com.sonar.trading.risk;

import com.sonar.trading.domain.entity.TradingStateEntity;
import com.sonar.trading.domain.repository.TradingStateRepository;
import com.sonar.trading.risk.rules.RiskRule;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultRiskEngineTest {

    private static RiskContext ctx(AccountRiskProfile profile) {
        return new RiskContext("acct-1", "SOLUSDT", "OPEN", "LONG", profile, null, 0,
                new BigDecimal("10000"), new BigDecimal("10000"), new BigDecimal("10000"),
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("50"), false, null);
    }

    private static AccountRiskProfile profile() {
        return new AccountRiskProfile("acct-1", new BigDecimal("0.005"), new BigDecimal("0.02"),
                new BigDecimal("2000"), new BigDecimal("0.03"), new BigDecimal("0.15"), 5, 5, true);
    }

    private static RiskRule ruleAt(int priority, Optional<RiskDecision> outcome) {
        return new RiskRule() {
            @Override public Optional<RiskDecision> evaluate(RiskContext context) { return outcome; }
            @Override public int priority() { return priority; }
        };
    }

    @Test
    void firstBlockingRuleInPriorityOrderWins() {
        RiskRule passesFirst = ruleAt(0, Optional.empty());
        RiskRule rejectsAtFive = ruleAt(5, Optional.of(RiskDecision.reject("priority-5 reason")));
        RiskRule wouldAlsoRejectAtTen = ruleAt(10, Optional.of(RiskDecision.reject("priority-10 reason")));

        TradingStateRepository repo = Mockito.mock(TradingStateRepository.class);
        when(repo.findByKeyStartingWith(anyString())).thenReturn(List.of());
        // rules deliberately passed out of priority order — engine must sort by priority itself
        DefaultRiskEngine engine = new DefaultRiskEngine(
                List.of(wouldAlsoRejectAtTen, rejectsAtFive, passesFirst), repo);

        RiskDecision decision = engine.evaluate(ctx(profile()));

        assertThat(decision.outcome()).isEqualTo(RiskDecision.Outcome.REJECT);
        assertThat(decision.reason()).isEqualTo("priority-5 reason");
    }

    @Test
    void approvesWhenNoRuleFires() {
        TradingStateRepository repo = Mockito.mock(TradingStateRepository.class);
        when(repo.findByKeyStartingWith(anyString())).thenReturn(List.of());
        DefaultRiskEngine engine = new DefaultRiskEngine(List.of(ruleAt(0, Optional.empty())), repo);

        assertThat(engine.evaluate(ctx(profile())).outcome()).isEqualTo(RiskDecision.Outcome.APPROVE);
    }

    @Test
    void pauseTradingOutcomeActivatesAndPersistsAutoPause() {
        RiskRule pausesOnDailyLoss = ruleAt(3, Optional.of(
                RiskDecision.pauseTrading(RiskDecision.PauseCause.DAILY_LOSS, "daily loss breached")));

        TradingStateRepository repo = Mockito.mock(TradingStateRepository.class);
        when(repo.findByKeyStartingWith(anyString())).thenReturn(List.of());
        when(repo.findById(anyString())).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        DefaultRiskEngine engine = new DefaultRiskEngine(List.of(pausesOnDailyLoss), repo);

        assertThat(engine.isAutoPauseActive("acct-1")).isFalse();
        engine.evaluate(ctx(profile()));

        assertThat(engine.isAutoPauseActive("acct-1")).isTrue();
        assertThat(engine.autoPauseCause("acct-1")).isEqualTo(RiskDecision.PauseCause.DAILY_LOSS);
        // one save for the auto_pause:acct-1 flag, one for the auto_pause_cause:acct-1 reason
        verify(repo, times(2)).save(any(TradingStateEntity.class));
    }

    @Test
    void clearAutoPauseResetsInMemoryStateAndDeletesCauseRow() {
        TradingStateRepository repo = Mockito.mock(TradingStateRepository.class);
        when(repo.findByKeyStartingWith(anyString())).thenReturn(List.of());
        when(repo.findById(anyString())).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        DefaultRiskEngine engine = new DefaultRiskEngine(List.of(), repo);

        engine.activateAutoPause("acct-1", RiskDecision.PauseCause.MAX_DRAWDOWN, "manual test");
        assertThat(engine.isAutoPauseActive("acct-1")).isTrue();

        engine.clearAutoPause("acct-1");
        assertThat(engine.isAutoPauseActive("acct-1")).isFalse();
        assertThat(engine.autoPauseCause("acct-1")).isNull();
    }
}
