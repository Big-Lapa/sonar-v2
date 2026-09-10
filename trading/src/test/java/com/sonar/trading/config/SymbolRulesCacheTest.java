package com.sonar.trading.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code SymbolRulesCache} talks straight to each venue's REST client with no seam to fake, so
 * these test the pure account→(venue,host) resolution the whole rewrite is about: the cache/client
 * key that decides whether two accounts share a fetch or get their own. {@code venueOf}/{@code
 * hostOf} are package-private specifically for this.
 *
 * <p>This is the exact gap that let the original bug ship: BingX was hardcoded to production
 * regardless of account (so even the sole bingx-vst demo account was sized off PRODUCTION
 * instrument filters), and Bybit resolved once at startup from whichever account happened to be
 * configured first — correct by accident, broken the moment a second Bybit account on a different
 * host exists. Nothing in the old test suite exercised "two accounts, same venue, different host"
 * because there was no seam to key on other than the venue name itself.
 */
class SymbolRulesCacheTest {

    private static AccountProps account(String id, String exchange, String baseUrl) {
        return new AccountProps(id, exchange, "key-" + id, "secret-" + id, baseUrl);
    }

    @Test
    void twoBybitAccountsOnDifferentHostsResolveToDifferentKeys() {
        AccountProps demo = account("bybit-demo", "bybit", "https://api-demo.bybit.com");
        AccountProps prod = account("bybit-prod", "bybit", "https://api.bybit.com");

        assertThat(SymbolRulesCache.hostOf(demo, SymbolRulesCache.venueOf(demo)))
                .isNotEqualTo(SymbolRulesCache.hostOf(prod, SymbolRulesCache.venueOf(prod)));
    }

    @Test
    void twoBybitAccountsOnTheSameHostResolveToTheSameKey() {
        // Two demo accounts (or any two accounts genuinely sharing a domain) should NOT trigger two
        // independent fetches of the same public data.
        AccountProps a = account("bybit-demo-1", "bybit", "https://api-demo.bybit.com");
        AccountProps b = account("bybit-demo-2", "bybit", "https://api-demo.bybit.com");

        assertThat(SymbolRulesCache.hostOf(a, SymbolRulesCache.venueOf(a)))
                .isEqualTo(SymbolRulesCache.hostOf(b, SymbolRulesCache.venueOf(b)));
    }

    @Test
    void twoBingxAccountsOnDifferentHostsResolveToDifferentKeys() {
        // The live bug this whole class was rewritten for: bingx-vst (demo) used to be sized off a
        // hardcoded PRODUCTION host regardless of its own baseUrl.
        AccountProps vst = account("bingx-vst", "bingx", "https://open-api-vst.bingx.com");
        AccountProps prod = account("bingx-prod", "bingx", "https://open-api.bingx.com");

        String vstHost = SymbolRulesCache.hostOf(vst, SymbolRulesCache.venueOf(vst));
        String prodHost = SymbolRulesCache.hostOf(prod, SymbolRulesCache.venueOf(prod));

        assertThat(vstHost).isNotEqualTo(prodHost);
        assertThat(vstHost).isEqualTo("https://open-api-vst.bingx.com");
        assertThat(prodHost).isEqualTo("https://open-api.bingx.com");
    }

    @Test
    void venueResolutionIsCaseInsensitiveAndNullSafe() {
        AccountProps lower = account("bingx-vst", "bingx", "https://open-api-vst.bingx.com");
        AccountProps blank = account("mystery", null, "https://example.com");

        assertThat(SymbolRulesCache.venueOf(lower)).isEqualTo("BINGX");
        assertThat(SymbolRulesCache.venueOf(blank)).isEqualTo("");
    }

    @Test
    void binanceResolvesToTheAccountsOwnDomainLikeEveryOtherVenue() {
        // A Binance testnet account must size off testnet's own exchangeInfo, not mainnet's — the
        // symbol list and LOT_SIZE/MIN_NOTIONAL filters genuinely differ between the two domains.
        AccountProps testnet = account("binance-demo", "binance", "https://testnet.binancefuture.com");
        AccountProps mainnet = account("binance-prod", "binance", "https://fapi.binance.com");

        assertThat(SymbolRulesCache.hostOf(testnet, SymbolRulesCache.venueOf(testnet)))
                .isEqualTo("https://testnet.binancefuture.com");
        assertThat(SymbolRulesCache.hostOf(mainnet, SymbolRulesCache.venueOf(mainnet)))
                .isEqualTo("https://fapi.binance.com");
    }

    @Test
    void getThrowsForAnUnknownAccountIdRatherThanGuessingAHost() {
        AccountRegistry accounts = new AccountRegistry(new org.springframework.mock.env.MockEnvironment());
        accounts.load(); // @PostConstruct isn't invoked outside a Spring context
        SymbolRulesCache cache = new SymbolRulesCache(accounts);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> cache.get("does-not-exist", "BTCUSDT"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does-not-exist");
    }
}
