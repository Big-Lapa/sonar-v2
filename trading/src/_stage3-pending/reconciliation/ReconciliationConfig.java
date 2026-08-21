package com.sonar.trading.reconciliation;

import com.sonar.trading.config.AccountContext;
import com.sonar.trading.config.TradingProperties;
import com.sonar.trading.execution.AccountExecutionAdapters;
import com.sonar.trading.execution.gateway.ExecutionGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires reconciliation (per-account since P4). No longer LIVE-conditional: the worker/service are
 * always beans and activation is driven by {@link AccountReconcilers} content — empty (PAPER-only)
 * means every cycle is a no-op and the health flag is never armed. @EnableScheduling lives on the
 * application class: hanging scheduling here silently disabled ALL schedulers in PAPER — including
 * the outbox publisher (caught by the platform-hardening smoke).
 */
@Configuration
@EnableConfigurationProperties(ReconciliationProperties.class)
public class ReconciliationConfig {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationConfig.class);

    /**
     * Legacy single-account LIVE fallback: with no {@code trading.accounts[]} registry, the
     * accounts path registers nothing — but {@code trading.exchange=LIVE} still runs the singleton
     * BingX gateway, which must keep reconciling exactly as before P4. Registers one BingX
     * reconciler on the legacy gateway under the legacy account id. Runs after all singletons are
     * initialized, so the registry-driven registrations (adapter factory {@code @PostConstruct})
     * have already happened and the emptiness check is authoritative.
     */
    @Bean
    SmartInitializingSingleton legacyLiveReconcilerRegistrar(AccountReconcilers reconcilers,
                                                             AccountExecutionAdapters accountAdapters,
                                                             ObjectProvider<ExecutionGateway> legacyGateway,
                                                             ReconciliationProperties props,
                                                             AccountContext accountContext,
                                                             TradingProperties tradingProps) {
        return () -> {
            if (!accountAdapters.isEmpty() || !"LIVE".equalsIgnoreCase(tradingProps.getExchange())) {
                return;
            }
            ExecutionGateway gateway = legacyGateway.getIfAvailable();
            if (gateway == null) {
                log.warn("[RECON] trading.exchange=LIVE but no legacy ExecutionGateway bean — reconciliation idle");
                return;
            }
            String accountId = accountContext.currentAccountId();
            reconcilers.register(new BingxAccountReconciler(accountId, gateway, props.getEquityAsset()));
            log.info("[RECON] legacy LIVE mode — registered BingX reconciler for account '{}'", accountId);
        };
    }
}
