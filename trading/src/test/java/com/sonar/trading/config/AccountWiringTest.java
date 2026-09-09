package com.sonar.trading.config;

import com.sonar.trading.execution.gateway.ExecutionGatewayProperties;
import com.sonar.trading.health.SystemHealthService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards the two mis-wirings that would be silent and expensive on a real-money account: two
 * accounts sharing one API key (the same exchange account under two names, one of them lying about
 * which venue's balance it shows), and credentials with no base URL (which used to mean inheriting
 * a demo domain by default — the exact way a production order could have gone to the VST sandbox).
 */
class AccountWiringTest {

    private static AccountRegistry registryOf(String... triples) {
        MockEnvironment env = new MockEnvironment();
        for (int i = 0; i < triples.length; i += 5) {
            String n = "trading.accounts[" + (i / 5) + "].";
            env.setProperty(n + "id", triples[i]);
            env.setProperty(n + "exchange", triples[i + 1]);
            if (triples[i + 2] != null) env.setProperty(n + "api-key", triples[i + 2]);
            if (triples[i + 3] != null) env.setProperty(n + "api-secret", triples[i + 3]);
            if (triples[i + 4] != null) env.setProperty(n + "base-url", triples[i + 4]);
        }
        AccountRegistry registry = new AccountRegistry(env);
        registry.load();
        return registry;
    }

    private static AccountWiring.AccountStacks wire(AccountRegistry accounts) {
        return new AccountWiring().accountStacks(accounts, new ExecutionGatewayProperties(),
                new TradingProperties(), Mockito.mock(SystemHealthService.class), new SimpleMeterRegistry());
    }

    @Test
    void twoAccountsOnOneVenueGetTheirOwnStacks() {
        AccountRegistry accounts = registryOf(
                "bingx-vst", "bingx", "key-demo", "secret-demo", "https://open-api-vst.bingx.com",
                "bingx-prod", "bingx", "key-prod", "secret-prod", "https://open-api.bingx.com");

        AccountWiring.AccountStacks stacks = wire(accounts);

        assertThat(stacks.readers().forAccount("bingx-vst")).isPresent();
        assertThat(stacks.readers().forAccount("bingx-prod")).isPresent();
        assertThat(stacks.readers().forAccount("bingx-vst"))
                .isNotEqualTo(stacks.readers().forAccount("bingx-prod"));
        assertThat(stacks.adapters().forAccount("bingx-vst"))
                .isNotEqualTo(stacks.adapters().forAccount("bingx-prod"));
    }

    @Test
    void refusesToStartWhenTwoAccountsShareAnApiKey() {
        AccountRegistry accounts = registryOf(
                "bingx-vst", "bingx", "same-key", "secret-a", "https://open-api-vst.bingx.com",
                "bingx-prod", "bingx", "same-key", "secret-b", "https://open-api.bingx.com");

        assertThatThrownBy(() -> wire(accounts))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("same API key");
    }

    @Test
    void refusesToStartWhenTwoAccountsShareAnId() {
        // Both registries key by (lower-cased) account id — a duplicate silently makes the second
        // entry win the map slot while AccountRegistry.byId() (a first-match scan) keeps returning
        // the first, so the panel and execution would disagree about whose credentials are in play.
        AccountRegistry accounts = registryOf(
                "bybit-demo", "bybit", "key-a", "secret-a", "https://api-demo.bybit.com",
                "BYBIT-DEMO", "bingx", "key-b", "secret-b", "https://open-api-vst.bingx.com");

        assertThatThrownBy(() -> wire(accounts))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate account id");
    }

    @Test
    void refusesToStartWhenCredentialsHaveNoBaseUrl() {
        AccountRegistry accounts = registryOf(
                "bingx-prod", "bingx", "key-prod", "secret-prod", null);

        assertThatThrownBy(() -> wire(accounts))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BASE_URL");
    }

    @Test
    void blankCredentialsAreAPlaceholder_notAStartupFailure() {
        // A row whose keys haven't been pasted in yet must stay visible in the panel rather than
        // taking the whole service down.
        AccountRegistry accounts = registryOf(
                "bingx-prod", "bingx", "", "", "https://open-api.bingx.com",
                "bybit-demo", "bybit", "key-bybit", "secret", "https://api-demo.bybit.com");

        AccountWiring.AccountStacks stacks = wire(accounts);

        assertThat(stacks.readers().forAccount("bingx-prod")).isEmpty();
        assertThat(stacks.readers().forAccount("bybit-demo")).isPresent();
    }

    @Test
    void pionexIsWiredReadOnly_noExecutionAdapter() {
        // Pionex's Futures API exposes no TP/SL surface, so it deliberately gets a reader and no
        // adapter — which is also what makes StopGuard skip it instead of alerting forever.
        AccountRegistry accounts = registryOf(
                "pionex-prod", "pionex", "key-pionex", "secret", "https://api.pionex.com");

        AccountWiring.AccountStacks stacks = wire(accounts);

        assertThat(stacks.readers().forAccount("pionex-prod")).isPresent();
        assertThat(stacks.adapters().forAccount("pionex-prod")).isEmpty();
    }

    @Test
    void binanceIsWiredWithBothReaderAndExecutionAdapter() {
        AccountRegistry accounts = registryOf(
                "binance-demo", "binance", "key", "secret", "https://testnet.binancefuture.com");

        AccountWiring.AccountStacks stacks = wire(accounts);

        assertThat(stacks.readers().forAccount("binance-demo")).isPresent();
        assertThat(stacks.adapters().forAccount("binance-demo")).isPresent();
    }

    @Test
    void unimplementedVenueIsSkippedWithoutFailingStartup() {
        AccountRegistry accounts = registryOf(
                "made-up-demo", "made-up-exchange", "key", "secret", "https://example.invalid");

        AccountWiring.AccountStacks stacks = wire(accounts);

        assertThat(stacks.readers().forAccount("made-up-demo")).isEmpty();
        assertThat(stacks.adapters().forAccount("made-up-demo")).isEmpty();
    }

    @Test
    void accountRegistryStopsAtAGapInTheNumbering() {
        // Documents the sharp edge the .env comments warn about: index 2 missing means accounts 3+
        // silently vanish, taking their monitoring with them.
        MockEnvironment env = new MockEnvironment();
        env.setProperty("trading.accounts[0].id", "a0");
        env.setProperty("trading.accounts[1].id", "a1");
        env.setProperty("trading.accounts[3].id", "a3");
        AccountRegistry registry = new AccountRegistry(env);
        registry.load();

        assertThat(registry.all()).extracting(AccountProps::id).containsExactly("a0", "a1");
    }
}
