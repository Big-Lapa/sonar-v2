package com.sonar.trading.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Exchange contract specs (stepSize/minQty/minNotional/tickSize), fetched directly from each
 * venue's public REST (no config-service in v2 — decided 2026-08-21). Cached in memory per
 * venue with a 1h TTL, same pattern as the universe cache (4.4): these endpoints are unauthenticated
 * and change rarely, so a per-call fetch would be wasteful and adds latency to sizing.
 */
@Component
public class SymbolRulesCache {

    private static final Logger log = LoggerFactory.getLogger(SymbolRulesCache.class);
    private static final Duration TTL = Duration.ofHours(1);

    private final RestClient binanceClient = RestClient.create("https://fapi.binance.com");
    private final RestClient bingxClient = RestClient.create("https://open-api.bingx.com");

    private final ConcurrentHashMap<String, VenueCache> byVenue = new ConcurrentHashMap<>();

    private record VenueCache(Instant fetchedAt, Map<String, SymbolRules> rules) {}

    /** {@code venue} is BINANCE or BINGX (case-insensitive); {@code symbol} in canonical Binance form. */
    public SymbolRules get(String venue, String symbol) {
        String key = venue == null ? "BINANCE" : venue.toUpperCase();
        VenueCache cache = byVenue.get(key);
        if (cache == null || Duration.between(cache.fetchedAt(), Instant.now()).compareTo(TTL) > 0) {
            cache = refresh(key);
        }
        SymbolRules rules = cache.rules().get(symbol);
        if (rules == null) {
            log.warn("SymbolRulesCache: no contract spec for {}/{} — falling back to conservative default",
                    key, symbol);
            return SymbolRules.DEFAULT;
        }
        return rules;
    }

    private synchronized VenueCache refresh(String venue) {
        VenueCache existing = byVenue.get(venue);
        if (existing != null && Duration.between(existing.fetchedAt(), Instant.now()).compareTo(TTL) <= 0) {
            return existing; // another thread refreshed while we waited for the lock
        }
        Map<String, SymbolRules> rules;
        try {
            rules = "BINGX".equals(venue) ? fetchBingx() : fetchBinance();
        } catch (RuntimeException e) {
            log.error("SymbolRulesCache: refresh failed for {} — keeping stale cache if any: {}",
                    venue, e.toString());
            if (existing != null) {
                return existing;
            }
            rules = Map.of();
        }
        VenueCache fresh = new VenueCache(Instant.now(), rules);
        byVenue.put(venue, fresh);
        return fresh;
    }

    @SuppressWarnings("unchecked")
    private Map<String, SymbolRules> fetchBinance() {
        Map<String, Object> body = binanceClient.get().uri("/fapi/v1/exchangeInfo")
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
     *  Binance form ({@code BASEQUOTE}, 4.0) so callers never juggle two symbol spellings. */
    @SuppressWarnings("unchecked")
    private Map<String, SymbolRules> fetchBingx() {
        Map<String, Object> body = bingxClient.get().uri("/openApi/swap/v2/quote/contracts")
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

    private static BigDecimal stepFromPrecision(Object precision) {
        if (precision == null) {
            return SymbolRules.DEFAULT.stepSize();
        }
        int p = Integer.parseInt(precision.toString());
        return BigDecimal.ONE.movePointLeft(p);
    }
}
