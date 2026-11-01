package com.sonar.trading.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Exchange contract specs (stepSize/minQty/minNotional/tickSize), fetched directly from each
 * venue's public REST (no config-service in v2 — decided 2026-08-21). Cached in memory with a 1h
 * TTL, same pattern as the universe cache: these endpoints are unauthenticated and change
 * rarely, so a per-call fetch would be wasteful and adds latency to sizing.
 *
 * <p><b>Keyed by resolved HOST, not by venue name.</b> Credentials and base URL moved to being
 * per-account (multi-account refactor), and BingX/Bybit each already have — or will soon have —
 * more than one account on genuinely different domains (BingX VST demo vs production; more Bybit
 * accounts are coming). A venue-keyed cache with one shared client silently answered every
 * account's sizing query from whichever domain that one client happened to point at: this class
 * used to hardcode BingX to production regardless of account (so even the sole existing bingx-vst
 * demo account was sized off PRODUCTION instrument filters, not the VST demo ones it actually
 * trades against — a live bug, not hypothetical), and Bybit's client resolved once at startup from
 * whichever Bybit account happened to be configured first — correct only by accident, and wrong
 * the moment a second Bybit account on a different host exists. Keying by host means two accounts
 * on the SAME domain share one fetch (no wasted calls), and two accounts on DIFFERENT domains never
 * share a set of specs that don't apply to them.
 *
 * <p>{@link #get} takes an <b>account id</b>, resolved here via {@link AccountRegistry} into
 * (venue, host) — callers (sizing) don't need to know account structure, just which account they're
 * sizing for. An id absent from the registry is a caller bug (every real caller has a live
 * {@code AccountProps} in hand already), so it throws rather than guessing a fallback host.
 */
@Component
public class SymbolRulesCache {

    private static final Logger log = LoggerFactory.getLogger(SymbolRulesCache.class);
    private static final Duration TTL = Duration.ofHours(1);

    private final AccountRegistry accounts;
    private final Map<String, RestClient> clientsByHost = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, VenueCache> byHost = new ConcurrentHashMap<>();

    public SymbolRulesCache(AccountRegistry accounts) {
        this.accounts = accounts;
    }

    private record VenueCache(Instant fetchedAt, Map<String, SymbolRules> rules) {}

    /** {@code symbol} in canonical Binance form (e.g. {@code BTCUSDT}). */
    public SymbolRules get(String accountId, String symbol) {
        AccountProps account = accounts.byId(accountId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "SymbolRulesCache.get: unknown account id '" + accountId + "'"));
        String venue = venueOf(account);
        String host = hostOf(account, venue);

        VenueCache cache = byHost.get(host);
        if (cache == null || Duration.between(cache.fetchedAt(), Instant.now()).compareTo(TTL) > 0) {
            cache = refresh(venue, host);
        }
        SymbolRules rules = cache.rules().get(symbol);
        if (rules == null) {
            log.warn("SymbolRulesCache: no contract spec for {}/{} ({}) — falling back to conservative default",
                    venue, symbol, host);
            return SymbolRules.DEFAULT;
        }
        return rules;
    }

    private RestClient clientFor(String host) {
        return clientsByHost.computeIfAbsent(host, RestClient::create);
    }

    static String venueOf(AccountProps account) {
        return account.exchange() == null ? "" : account.exchange().toUpperCase(Locale.ROOT);
    }

    /**
     * The cache/client key for one account — a pure function, package-private specifically so the
     * account→host resolution (the whole point of this class's rewrite) can be unit-tested without
     * a network call. Every venue (BingX, Bybit, Binance) resolves to the account's own domain —
     * a Binance testnet account must size off testnet's {@code /fapi/v1/exchangeInfo}, not
     * mainnet's: the symbol list and LOT_SIZE/MIN_NOTIONAL filters genuinely differ between the
     * two, and sizing off the wrong one produces exchange rejections (-1111 precision, -4164 min
     * notional) at order placement instead of at sizing.
     */
    static String hostOf(AccountProps account, String venue) {
        return account.baseUrl();
    }

    private synchronized VenueCache refresh(String venue, String host) {
        VenueCache existing = byHost.get(host);
        if (existing != null && Duration.between(existing.fetchedAt(), Instant.now()).compareTo(TTL) <= 0) {
            return existing; // another thread refreshed while we waited for the lock
        }
        Map<String, SymbolRules> rules;
        try {
            RestClient client = clientFor(host);
            rules = switch (venue) {
                case "BINGX" -> fetchBingx(client);
                case "BYBIT" -> fetchBybit(client);
                default -> fetchBinance(client);
            };
        } catch (RuntimeException e) {
            log.error("SymbolRulesCache: refresh failed for {} ({}) — keeping stale cache if any: {}",
                    venue, host, e.toString());
            if (existing != null) {
                return existing;
            }
            rules = Map.of();
        }
        VenueCache fresh = new VenueCache(Instant.now(), rules);
        byHost.put(host, fresh);
        return fresh;
    }

    @SuppressWarnings("unchecked")
    private Map<String, SymbolRules> fetchBinance(RestClient client) {
        Map<String, Object> body = client.get().uri("/fapi/v1/exchangeInfo")
                .retrieve().body(Map.class);
        List<Map<String, Object>> symbols = (List<Map<String, Object>>) body.get("symbols");
        Map<String, SymbolRules> out = new ConcurrentHashMap<>();
        for (Map<String, Object> s : symbols) {
            String symbol = (String) s.get("symbol");
            BigDecimal stepSize = null, minQty = null, tickSize = null, minNotional = null;
            for (Map<String, Object> filter : (List<Map<String, Object>>) s.get("filters")) {
                switch ((String) filter.get("filterType")) {
                    case "LOT_SIZE" -> {
                        stepSize = new BigDecimal((String) filter.get("stepSize"));
                        minQty = new BigDecimal((String) filter.get("minQty"));
                    }
                    case "PRICE_FILTER" -> tickSize = new BigDecimal((String) filter.get("tickSize"));
                    case "MIN_NOTIONAL" -> minNotional = new BigDecimal((String) filter.get("notional"));
                    default -> { }
                }
            }
            out.put(symbol, new SymbolRules(
                    stepSize != null ? stepSize : SymbolRules.DEFAULT.stepSize(),
                    minQty != null ? minQty : SymbolRules.DEFAULT.minQty(),
                    minNotional != null ? minNotional : SymbolRules.DEFAULT.minNotional(),
                    tickSize != null ? tickSize : SymbolRules.DEFAULT.tickSize()));
        }
        log.info("SymbolRulesCache: refreshed BINANCE contracts, {} symbols", out.size());
        return out;
    }

    /** BingX canonical symbol form is {@code BASE-QUOTE}; keyed here by the platform's canonical
     *  Binance form ({@code BASEQUOTE}) so callers never juggle two symbol spellings. */
    @SuppressWarnings("unchecked")
    private Map<String, SymbolRules> fetchBingx(RestClient client) {
        Map<String, Object> body = client.get().uri("/openApi/swap/v2/quote/contracts")
                .retrieve().body(Map.class);
        List<Map<String, Object>> contracts = (List<Map<String, Object>>) body.get("data");
        Map<String, SymbolRules> out = new ConcurrentHashMap<>();
        for (Map<String, Object> c : contracts) {
            String bingxSymbol = (String) c.get("symbol"); // e.g. "SOL-USDT"
            String canonical = bingxSymbol.replace("-", "");
            BigDecimal stepSize = stepFromPrecision(c.get("quantityPrecision"));
            BigDecimal tickSize = stepFromPrecision(c.get("pricePrecision"));
            BigDecimal minQty = c.get("tradeMinQuantity") != null
                    ? new BigDecimal(c.get("tradeMinQuantity").toString()) : SymbolRules.DEFAULT.minQty();
            BigDecimal minNotional = c.get("tradeMinUSDT") != null
                    ? new BigDecimal(c.get("tradeMinUSDT").toString()) : SymbolRules.DEFAULT.minNotional();
            out.put(canonical, new SymbolRules(stepSize, minQty, minNotional, tickSize));
        }
        log.info("SymbolRulesCache: refreshed BINGX contracts, {} symbols", out.size());
        return out;
    }

    /**
     * Bybit's symbol form IS the platform's canonical form (no dash) — no key translation needed.
     *
     * <p>Paginated: {@code /v5/market/instruments-info} caps at 1000 rows/page and Bybit lists
     * well over 500 linear USDT-perpetual instruments — a single unpaginated page silently drops
     * the tail (mostly the less-liquid symbols, where a non-default step/tick size is more likely,
     * so a missed row means wrong lot/price rounding rather than just a missing lookup).
     */
    @SuppressWarnings("unchecked")
    private Map<String, SymbolRules> fetchBybit(RestClient client) {
        Map<String, SymbolRules> out = new ConcurrentHashMap<>();
        String cursor = null;
        for (int page = 0; page < 10; page++) {
            String pageCursor = cursor;
            Map<String, Object> body = client.get()
                    .uri(uri -> {
                        var b = uri.path("/v5/market/instruments-info").queryParam("category", "linear");
                        if (pageCursor != null && !pageCursor.isBlank()) {
                            b = b.queryParam("cursor", pageCursor);
                        }
                        return b.build();
                    })
                    .retrieve().body(Map.class);
            Map<String, Object> result = (Map<String, Object>) body.get("result");
            List<Map<String, Object>> list = result == null ? List.of() : (List<Map<String, Object>>) result.get("list");
            out.putAll(parseBybitInstruments(list));
            cursor = result == null ? null : (String) result.get("nextPageCursor");
            if (cursor == null || cursor.isBlank()) {
                break;
            }
        }
        log.info("SymbolRulesCache: refreshed BYBIT contracts, {} symbols", out.size());
        return out;
    }

    private Map<String, SymbolRules> parseBybitInstruments(List<Map<String, Object>> list) {
        Map<String, SymbolRules> out = new ConcurrentHashMap<>();
        for (Map<String, Object> s : list) {
            String symbol = (String) s.get("symbol");
            Map<String, Object> lotSize = (Map<String, Object>) s.get("lotSizeFilter");
            Map<String, Object> priceFilter = (Map<String, Object>) s.get("priceFilter");
            BigDecimal stepSize = lotSize != null && lotSize.get("qtyStep") != null
                    ? new BigDecimal(lotSize.get("qtyStep").toString()) : SymbolRules.DEFAULT.stepSize();
            BigDecimal minQty = lotSize != null && lotSize.get("minOrderQty") != null
                    ? new BigDecimal(lotSize.get("minOrderQty").toString()) : SymbolRules.DEFAULT.minQty();
            BigDecimal tickSize = priceFilter != null && priceFilter.get("tickSize") != null
                    ? new BigDecimal(priceFilter.get("tickSize").toString()) : SymbolRules.DEFAULT.tickSize();
            // Bybit's instruments-info has no direct min-notional field — conservative default kept.
            out.put(symbol, new SymbolRules(stepSize, minQty, SymbolRules.DEFAULT.minNotional(), tickSize));
        }
        return out;
    }

    private static BigDecimal stepFromPrecision(Object precision) {
        if (precision == null) {
            return SymbolRules.DEFAULT.stepSize();
        }
        int p = Integer.parseInt(precision.toString());
        return BigDecimal.ONE.movePointLeft(p);
    }
}
