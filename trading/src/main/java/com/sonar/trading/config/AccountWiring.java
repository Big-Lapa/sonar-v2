package com.sonar.trading.config;

import com.sonar.trading.exchange.bingx.BingxFuturesClient;
import com.sonar.trading.exchange.bingx.BingxFuturesProperties;
import com.sonar.trading.exchange.bingx.http.BingxFuturesHttpService;
import com.sonar.trading.exchange.bybit.BybitFuturesClient;
import com.sonar.trading.exchange.bybit.BybitFuturesProperties;
import com.sonar.trading.exchange.bybit.http.BybitFuturesHttpService;
import com.sonar.trading.exchange.pionex.PionexFuturesClient;
import com.sonar.trading.exchange.pionex.PionexFuturesProperties;
import com.sonar.trading.exchange.pionex.http.PionexFuturesHttpService;
import com.sonar.trading.exchange.spi.ExecutionAdapter;
import com.sonar.trading.execution.adapter.BybitExecutionAdapter;
import com.sonar.trading.execution.adapter.ExecutionAdapterRegistry;
import com.sonar.trading.execution.adapter.LiveExecutionAdapter;
import com.sonar.trading.execution.gateway.BingxExecutionGateway;
import com.sonar.trading.execution.gateway.BybitExecutionGateway;
import com.sonar.trading.execution.gateway.ExecutionGatewayProperties;
import com.sonar.trading.execution.gateway.TransientErrorClassifier;
import com.sonar.trading.health.SystemHealthService;
import com.sonar.trading.reconciliation.AccountReader;
import com.sonar.trading.reconciliation.AccountReaderRegistry;
import com.sonar.trading.reconciliation.BingxAccountReader;
import com.sonar.trading.reconciliation.BybitAccountReader;
import com.sonar.trading.reconciliation.PionexAccountReader;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Builds one client stack — REST client, {@link AccountReader}, and (where the venue supports
 * execution) gateway + {@link ExecutionAdapter} — <b>per account</b>, from the credentials each
 * account carries in {@code TRADING_ACCOUNTS_N_*}.
 *
 * <p>This replaces the previous one-bean-per-venue wiring, which made two accounts on the same
 * exchange impossible: they shared a single client, so a BingX production account would have read
 * the VST demo balance and could have sent a real order to the demo domain (or the reverse). Beans
 * are static, accounts are discovered from the environment, so the stacks live in maps built here
 * rather than as {@code @Bean} methods per venue.
 *
 * <p>Resilience4j scoping follows the venue/account split of what is actually being protected:
 * <ul>
 *   <li><b>Rate limiter — per venue.</b> Exchanges budget by IP as well as by key, and every
 *       account here shares one process and one IP, so two BingX accounts must draw from one
 *       bucket or they can jointly overrun the venue limit and get the whole IP banned.</li>
 *   <li><b>Circuit breaker and retry — per account.</b> A revoked key or a wedged account should
 *       trip only itself; the neighbouring account on the same venue is healthy and must keep
 *       trading.</li>
 * </ul>
 *
 * <p>Startup is fail-fast on the two mis-wirings that would be dangerous and silent (see
 * {@link #validate}); an account with blank credentials is not an error — it stays registered and
 * visible in the panel with no client, which is how a placeholder row (keys not pasted in yet)
 * behaves.
 */
@Configuration
@EnableConfigurationProperties(ExecutionGatewayProperties.class)
public class AccountWiring {

    private static final Logger log = LoggerFactory.getLogger(AccountWiring.class);

    /** Venues whose execution adapter exists; others are read-only (Pionex, today). */
    private static final String BINGX = "bingx";
    private static final String BYBIT = "bybit";
    private static final String PIONEX = "pionex";

    private final Map<String, RateLimiter> rateLimitersByVenue = new HashMap<>();

    @Bean
    public AccountStacks accountStacks(AccountRegistry accounts,
                                       ExecutionGatewayProperties gatewayProps,
                                       TradingProperties tradingProps,
                                       SystemHealthService healthService,
                                       MeterRegistry meterRegistry) {
        validate(accounts);

        Map<String, AccountReader> readers = new LinkedHashMap<>();
        Map<String, ExecutionAdapter> adapters = new LinkedHashMap<>();

        for (AccountProps account : accounts.all()) {
            String id = account.id().toLowerCase(Locale.ROOT);
            String venue = account.exchange() == null ? "" : account.exchange().toLowerCase(Locale.ROOT);

            if (!account.isConfigured()) {
                log.warn("[wiring] {} ({}) — no API credentials configured, account is visible but not connected",
                        account.id(), venue);
                continue;
            }

            switch (venue) {
                case BINGX -> {
                    BingxFuturesClient client = new BingxFuturesHttpService(bingxProps(account));
                    readers.put(id, new BingxAccountReader(client));
                    adapters.put(id, new LiveExecutionAdapter(account.id(),
                            new BingxExecutionGateway(client, retry(id, gatewayProps), rateLimiter(venue, gatewayProps),
                                    breaker(id, gatewayProps), healthService, meterRegistry,
                                    gatewayProps.getRateLimiter().getCloseWaitMs(), account.id()),
                            tradingProps, client));
                }
                case BYBIT -> {
                    BybitFuturesClient client = new BybitFuturesHttpService(bybitProps(account));
                    readers.put(id, new BybitAccountReader(client));
                    adapters.put(id, new BybitExecutionAdapter(account.id(),
                            new BybitExecutionGateway(client, retry(id, gatewayProps), rateLimiter(venue, gatewayProps),
                                    breaker(id, gatewayProps), healthService, meterRegistry,
                                    gatewayProps.getRateLimiter().getCloseWaitMs(), account.id()),
                            tradingProps, client));
                }
                case PIONEX -> {
                    // Read-only venue: balances and positions only. Pionex's Futures API (Beta)
                    // exposes no TP/SL surface at all, so no execution adapter is wired and
                    // StopGuard skips this account entirely rather than alerting on a check it
                    // cannot perform.
                    PionexFuturesClient client = new PionexFuturesHttpService(pionexProps(account));
                    readers.put(id, new PionexAccountReader(client));
                }
                default -> {
                    log.warn("[wiring] {} — venue '{}' is not implemented, account stays unconnected",
                            account.id(), venue);
                    continue;
                }
            }

            log.info("[wiring] {} -> venue={} url={} key={} execution={}",
                    account.id(), venue, account.baseUrl(), account.keyFingerprint(),
                    adapters.containsKey(id) ? "yes" : "read-only");
        }

        return new AccountStacks(new AccountReaderRegistry(readers), new ExecutionAdapterRegistry(adapters));
    }

    @Bean
    public AccountReaderRegistry accountReaderRegistry(AccountStacks stacks) {
        return stacks.readers();
    }

    @Bean
    public ExecutionAdapterRegistry executionAdapterRegistry(AccountStacks stacks) {
        return stacks.adapters();
    }

    /** The pair of per-account registries, built together so one pass over the accounts wires both. */
    public record AccountStacks(AccountReaderRegistry readers, ExecutionAdapterRegistry adapters) {}

    /**
     * Refuses to start on the two mis-wirings that are both silent and expensive:
     * <ol>
     *   <li>two accounts sharing one API key — they would be the same account wearing two names,
     *       and the panel would show a "production" row reporting a demo balance;</li>
     *   <li>credentials without a base URL — nothing to fall back to on purpose, precisely so a
     *       real-money account cannot inherit a demo default domain.</li>
     * </ol>
     * A blank-credential account is deliberately not an error: that is a placeholder row.
     */
    private static void validate(AccountRegistry accounts) {
        Map<String, String> keyOwner = new HashMap<>();
        for (AccountProps account : accounts.all()) {
            if (!account.isConfigured()) {
                continue;
            }
            if (!account.hasBaseUrl()) {
                throw new IllegalStateException("account '" + account.id() + "' has API credentials but no "
                        + "TRADING_ACCOUNTS_*_BASE_URL — refusing to guess a domain for it");
            }
            String previous = keyOwner.putIfAbsent(account.apiKey(), account.id());
            if (previous != null) {
                throw new IllegalStateException("accounts '" + previous + "' and '" + account.id()
                        + "' are configured with the same API key — they would read and trade the same"
                        + " exchange account under two names");
            }
        }
    }

    // ---- Per-account venue properties (no venue-wide defaults; everything comes from the account) ----

    private static BingxFuturesProperties bingxProps(AccountProps account) {
        BingxFuturesProperties props = new BingxFuturesProperties();
        props.setApiKey(account.apiKey());
        props.setApiSecret(account.apiSecret());
        props.setBaseUrl(account.baseUrl());
        return props;
    }

    private static BybitFuturesProperties bybitProps(AccountProps account) {
        BybitFuturesProperties props = new BybitFuturesProperties();
        props.setApiKey(account.apiKey());
        props.setApiSecret(account.apiSecret());
        props.setBaseUrl(account.baseUrl());
        return props;
    }

    private static PionexFuturesProperties pionexProps(AccountProps account) {
        PionexFuturesProperties props = new PionexFuturesProperties();
        props.setApiKey(account.apiKey());
        props.setApiSecret(account.apiSecret());
        props.setBaseUrl(account.baseUrl());
        return props;
    }

    // ---- Resilience4j instances (see the class javadoc for why the scopes differ) ----

    private static Retry retry(String accountId, ExecutionGatewayProperties props) {
        var r = props.getRetry();
        return Retry.of(accountId + "-execution", RetryConfig.custom()
                .maxAttempts(r.getMaxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialBackoff(
                        Duration.ofMillis(r.getWaitMs()), r.getBackoffMultiplier()))
                .retryOnException(TransientErrorClassifier::isTransient)
                .failAfterMaxAttempts(true)
                .build());
    }

    /** Shared per venue — the exchange's IP budget is shared by every account in this process. */
    private RateLimiter rateLimiter(String venue, ExecutionGatewayProperties props) {
        return rateLimitersByVenue.computeIfAbsent(venue, v -> {
            var rl = props.getRateLimiter();
            // Zero timeout → acquirePermission() is non-blocking; CLOSE/REDUCE waiting is handled
            // in the gateway so entries can fail fast while closes are given a grace budget.
            return RateLimiter.of(v + "-execution", RateLimiterConfig.custom()
                    .limitForPeriod(rl.getLimitForPeriod())
                    .limitRefreshPeriod(Duration.ofMillis(rl.getRefreshPeriodMs()))
                    .timeoutDuration(Duration.ZERO)
                    .build());
        });
    }

    private static CircuitBreaker breaker(String accountId, ExecutionGatewayProperties props) {
        var cb = props.getCircuitBreaker();
        return CircuitBreaker.of(accountId + "-execution", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(cb.getSlidingWindowSize())
                .minimumNumberOfCalls(cb.getMinimumNumberOfCalls())
                .failureRateThreshold(cb.getFailureRateThreshold())
                .waitDurationInOpenState(Duration.ofMillis(cb.getWaitDurationInOpenStateMs()))
                .permittedNumberOfCallsInHalfOpenState(cb.getPermittedCallsInHalfOpen())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build());
    }
}
