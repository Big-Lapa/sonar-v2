package com.sonar.trading.execution;

import com.sonar.trading.config.ConfigCache;
import com.sonar.trading.config.TradingProperties;
import com.sonar.trading.exchange.dto.AdapterOrderRequest;
import com.sonar.trading.exchange.dto.AdapterOrderResult;
import com.sonar.trading.exchange.dto.TpSlAttachment;
import com.sonar.trading.exchange.model.AdapterOrderSide;
import com.sonar.trading.exchange.model.AdapterOrderType;
import com.sonar.trading.exchange.model.OrderIntent;
import com.sonar.trading.exchange.spi.ExecutionAdapter;
import com.sonar.trading.signal.dto.TradingSignalPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Execution Engine (T5): dispatches orders to the active adapter.
 * The active adapter is the {@code @Primary} bean chosen by {@code trading.exchange}
 * (PAPER → paper, LIVE → BingX) in {@link ExecutionAdapterConfig} (T13).
 *
 * <p>T23: CLOSE_REDUCE orders use LIMIT PostOnly (maker) when
 * {@code trading.maker-close.enabled=true} and a fresh bid/ask is available.
 * Falls back to MARKET if bid/ask is absent. ENTRY orders are always MARKET.
 *
 * <p>T23: ENTRY orders can carry attached TP/SL orders (exchange-side OCO) when
 * {@code trading.tp-sl.enabled=true}. Prices are derived as % from entry price.
 */
@Service
public class ExecutionEngine implements SignalExecutionPort {

    private static final Logger log = LoggerFactory.getLogger(ExecutionEngine.class);

    // BingX Swap V2 PostOnly time-in-force value (T23). Verify against API docs before enabling.
    private static final String TIF_POST_ONLY = "PostOnly";

    private final ExecutionAdapter adapter;
    private final PriceFeed priceCache;
    private final TradingProperties props;
    private final ConfigCache configCache;

    public ExecutionEngine(ExecutionAdapter adapter,
                            PriceFeed priceCache,
                            TradingProperties props,
                            ConfigCache configCache) {
        this.adapter = adapter;
        this.priceCache = priceCache;
        this.props = props;
        this.configCache = configCache;
    }

    @Override
    public String activeExchange() {
        return adapter.adapterName();
    }

    @Override
    public AdapterOrderResult execute(TradingSignalPayload signal, BigDecimal quantity, String clientOrderId) {
        return execute(signal, quantity, clientOrderId, adapter);
    }

    /**
     * Fan-out routing (P3): build the order from the signal and place it on the given account
     * {@code execAdapter} rather than the default {@code @Primary} one. Order-building logic
     * (maker-close, TP/SL, side mapping) is identical regardless of the target account.
     */
    @Override
    public AdapterOrderResult execute(TradingSignalPayload signal, BigDecimal quantity,
                                      String clientOrderId, ExecutionAdapter execAdapter) {
        OrderIntent intent = resolveIntent(signal);
        AdapterOrderSide adapterSide = resolveAdapterSide(signal);
        // Execution exchange of this leg: clamp and rounding specs are taken from it (V10).
        String venue = execAdapter.adapterName();

        AdapterOrderType type = AdapterOrderType.MARKET;
        BigDecimal price = priceCache.getPrice(signal.symbol()).orElse(BigDecimal.ZERO);
        String timeInForce = null;
        TpSlAttachment tpSl = null;

        if (intent == OrderIntent.CLOSE_REDUCE && props.getMakerClose().isEnabled()) {
            // EXPERIMENTAL, fire-and-forget: there is no fill-wait / cancel / MARKET fallback yet,
            // so a PostOnly that the venue rejects or never fills leaves the CLOSE hanging until
            // the time-stop backstop. See TradingProperties.MakerCloseProps.
            log.warn("ExecutionEngine: maker-close is ENABLED — experimental fire-and-forget PostOnly "
                    + "CLOSE has no fill confirmation/fallback; an unfilled leg hangs until time-stop");
            Optional<BigDecimal> makerPrice = resolveMakerPrice(venue, signal.symbol(), adapterSide);
            if (makerPrice.isPresent()) {
                type = AdapterOrderType.LIMIT;
                price = makerPrice.get();
                timeInForce = TIF_POST_ONLY;
                log.info("ExecutionEngine: maker-close LIMIT PostOnly symbol={} side={} price={}",
                        signal.symbol(), adapterSide, price);
            } else {
                log.warn("ExecutionEngine: maker-close enabled but no bid/ask for {} — MARKET fallback",
                        signal.symbol());
            }
        }

        Integer leverage = null;
        if (intent == OrderIntent.ENTRY && price.signum() > 0) {
            tpSl = resolveTpSl(venue, signal, price, quantity);
            leverage = resolveLeverage(signal);
        }

        AdapterOrderRequest request = new AdapterOrderRequest(
                signal.symbol(), adapterSide, type, quantity, price, clientOrderId, intent, timeInForce,
                tpSl, leverage
        );

        log.info("ExecutionEngine: symbol={} action={} side={} type={} qty={} price={} tif={} tpSl={} adapter={}",
                signal.symbol(), signal.action(), adapterSide, type, quantity, price, timeInForce,
                tpSl != null ? "tp=" + tpSl.takeProfitPrice() + "/sl=" + tpSl.stopLossPrice() : "none",
                execAdapter.adapterName());

        return execAdapter.placeOrder(request);
    }

    /**
     * Maps domain signal (LONG/SHORT + OPEN/CLOSE) to exchange BUY/SELL.
     * LONG OPEN → BUY, LONG CLOSE → SELL, SHORT OPEN → SELL, SHORT CLOSE → BUY
     */
    private AdapterOrderSide resolveAdapterSide(TradingSignalPayload signal) {
        boolean isLong = "LONG".equals(signal.side());
        boolean isOpen = "OPEN".equals(signal.action()) || "FLIP".equals(signal.action());
        return (isLong == isOpen) ? AdapterOrderSide.BUY : AdapterOrderSide.SELL;
    }

    /**
     * Risk intent for the Execution Gateway (T16, ADR-0018). CLOSE/REDUCE reduce exposure and must
     * keep flowing under degradation; OPEN/FLIP add exposure and are gated. The FLIP close-leg is
     * Phase 2 (T18); for now the action maps directly.
     */
    private OrderIntent resolveIntent(TradingSignalPayload signal) {
        String action = signal.action();
        return ("CLOSE".equals(action) || "REDUCE".equals(action))
                ? OrderIntent.CLOSE_REDUCE
                : OrderIntent.ENTRY;
    }

    /**
     * Maker price for CLOSE_REDUCE orders (T23):
     *   SELL (LONG close) → place at bestAsk (sits in book, no spread crossing)
     *   BUY  (SHORT close) → place at bestBid
     * Optional priceOffsetTicks shifts the price N ticks away from market (default 0).
     */
    private Optional<BigDecimal> resolveMakerPrice(String venue, String symbol, AdapterOrderSide side) {
        int offsetTicks = props.getMakerClose().getPriceOffsetTicks();
        if (side == AdapterOrderSide.SELL) {
            return priceCache.getBestAsk(symbol)
                    .map(ask -> applyTickOffset(venue, symbol, ask, offsetTicks));
        } else {
            return priceCache.getBestBid(symbol)
                    .map(bid -> applyTickOffset(venue, symbol, bid, -offsetTicks));
        }
    }

    /**
     * TP/SL prices for ENTRY orders (T23 + PR-D per-strategy override).
     * LONG:  TP = entryPrice * (1 + tpPct), SL = entryPrice * (1 - slPct)
     * SHORT: TP = entryPrice * (1 - tpPct), SL = entryPrice * (1 + slPct)
     *
     * <p>Priority: strategy params {@code slPct}/{@code tpPct} from config-service (pair
     * both-or-neither; their presence ENABLES TP/SL for the strategy regardless of the global
     * {@code trading.tp-sl.enabled}) → otherwise the global config as before. Partial take-profit
     * ({@code partialTpEnabled/tp1Pct/tp1QtyPct}) adds the tp1 price/quantity to the attachment;
     * moving the stop to breakeven after the partial fill is not automated yet.
     */
    @Override
    public String validateStrategyTpSlParams(TradingSignalPayload signal) {
        // ADR-0031: explicit absolute prices (discretionary-swing-service) — own validation path,
        // the % strategy params are irrelevant here. Same "unpaired is fatal" logic (review #88):
        // one price without the other — a position without a stop, reject BEFORE entry.
        if (signal.slPrice() != null || signal.tpPrice() != null) {
            BigDecimal sl = parsePrice(signal.slPrice());
            BigDecimal tp = parsePrice(signal.tpPrice());
            if (sl == null || tp == null || sl.signum() <= 0 || tp.signum() <= 0) {
                return "BAD_EXPLICIT_TP_SL: strategy " + signal.strategyId()
                        + " slPrice='" + signal.slPrice() + "' tpPrice='" + signal.tpPrice()
                        + "' — unpaired/non-numeric (a position without a stop is not allowed)";
            }
            return null;
        }
        String slRaw = strategyParam(signal, "slPct");
        String tpRaw = strategyParam(signal, "tpPct");
        if (slRaw == null && tpRaw == null) {
            return null; // the strategy does not declare per-strategy TP/SL — the global config applies
        }
        BigDecimal slPct = strategyParamDecimal(signal, "slPct");
        BigDecimal tpPct = strategyParamDecimal(signal, "tpPct");
        if (slPct == null || tpPct == null || slPct.signum() <= 0 || tpPct.signum() <= 0) {
            return "BAD_STRATEGY_PARAMS: strategy " + signal.strategyId()
                    + " slPct='" + slRaw + "' tpPct='" + tpRaw
                    + "' — unpaired/non-numeric (a position without a stop is not allowed)";
        }
        return null;
    }

    private TpSlAttachment resolveTpSl(String venue, TradingSignalPayload signal, BigDecimal entryPrice, BigDecimal quantity) {
        // The strategy DECLARED per-strategy TP/SL (any of the keys is present) — a broken set is
        // FATAL for entry: a typo ("1,0") produced an unpaired value → fallback to the disabled
        // global config → a position with no exchange stop at all until the 12h backstop (review #88).
        // Normally this is rejected BEFORE Tx1 in SignalProcessor via validateStrategyTpSlParams();
        // the throw here is a safety net in case a new execution path bypasses that check.
        String reason = validateStrategyTpSlParams(signal);
        if (reason != null) {
            throw new IllegalStateException(reason);
        }

        // ADR-0031: absolute levels (swing high/low + volume-profile HVN) have already been computed
        // by the SENDER for this specific trade — % of entryPrice makes no sense here (the level
        // sits where the real market structure is, not at a fixed percentage). referencePrice
        // is deliberately NOT passed: TpSlAttachment.rescaledTo() must not scale
        // absolute technical levels when the actual fill differs from referencePrice (that would distort
        // the swing/HVN target) — the two-argument constructor leaves referencePrice/tickSize null.
        if (signal.slPrice() != null || signal.tpPrice() != null) {
            BigDecimal explicitSl = parsePrice(signal.slPrice());
            BigDecimal explicitTp = parsePrice(signal.tpPrice());
            return new TpSlAttachment(
                    roundToTickSize(venue, signal.symbol(), explicitTp),
                    roundToTickSize(venue, signal.symbol(), explicitSl));
        }

        BigDecimal slPct = strategyParamDecimal(signal, "slPct");
        BigDecimal tpPct = strategyParamDecimal(signal, "tpPct");
        if (slPct == null) {
            TradingProperties.TpSlProps cfg = props.getTpSl();
            if (!cfg.isEnabled()) return null;
            slPct = cfg.getStopLossPercent();
            tpPct = cfg.getTakeProfitPercent();
        }

        boolean isLong = "LONG".equals(signal.side());
        BigDecimal hundred = new BigDecimal("100");
        BigDecimal tpFactor = tpPct.divide(hundred, 10, RoundingMode.HALF_UP);
        BigDecimal slFactor = slPct.divide(hundred, 10, RoundingMode.HALF_UP);

        BigDecimal tp = isLong
                ? entryPrice.multiply(BigDecimal.ONE.add(tpFactor))
                : entryPrice.multiply(BigDecimal.ONE.subtract(tpFactor));
        BigDecimal sl = isLong
                ? entryPrice.multiply(BigDecimal.ONE.subtract(slFactor))
                : entryPrice.multiply(BigDecimal.ONE.add(slFactor));

        BigDecimal tp1Price = null;
        BigDecimal tp1Qty = null;
        if ("true".equalsIgnoreCase(strategyParam(signal, "partialTpEnabled"))) {
            // Reminder on every entry: moving the stop to breakeven after TP1 is NOT automated —
            // after the partial take-profit the SL stays at the original level for the remainder (more conservative than TV; review #88).
            log.warn("partialTp {}: TP1 leg will be placed, but there is NO auto-move of SL to breakeven",
                    signal.strategyId());
            BigDecimal tp1Pct = strategyParamDecimal(signal, "tp1Pct");
            BigDecimal tp1QtyPct = strategyParamDecimal(signal, "tp1QtyPct");
            if (tp1Pct != null && tp1QtyPct != null && tp1QtyPct.signum() > 0) {
                BigDecimal tp1Factor = tp1Pct.divide(hundred, 10, RoundingMode.HALF_UP);
                BigDecimal rawTp1 = isLong
                        ? entryPrice.multiply(BigDecimal.ONE.add(tp1Factor))
                        : entryPrice.multiply(BigDecimal.ONE.subtract(tp1Factor));
                tp1Price = roundToTickSize(venue, signal.symbol(), rawTp1);
                tp1Qty = roundToStepSize(venue, signal.symbol(),
                        quantity.multiply(tp1QtyPct).divide(hundred, 10, RoundingMode.HALF_UP));
                if (tp1Qty.signum() <= 0 || tp1Qty.compareTo(quantity) >= 0) {
                    log.warn("partialTp for {} produced unusable tp1Qty={} of {} — skipping partial leg",
                            signal.symbol(), tp1Qty, quantity);
                    tp1Price = null;
                    tp1Qty = null;
                }
            }
        }

        // referencePrice + tickSize travel together with the levels: an adapter that places conditional legs
        // as separate orders after the fill (Binance) will recalculate them from the actual entry price
        // via TpSlAttachment.rescaledTo(). The entryPrice here is the last candle close from
        // spot, up to a minute old; strategy percentages must be computed from what we actually entered at.
        return new TpSlAttachment(
                roundToTickSize(venue, signal.symbol(), tp),
                roundToTickSize(venue, signal.symbol(), sl),
                tp1Price,
                tp1Qty,
                entryPrice,
                tickSizeFor(venue, signal.symbol())
        );
    }

    /** Target ENTRY leverage from strategy params ({@code leverage}); null = leave the exchange leverage untouched. */
    private Integer resolveLeverage(TradingSignalPayload signal) {
        BigDecimal lev = strategyParamDecimal(signal, "leverage");
        if (lev == null) {
            return null;
        }
        int v = lev.intValue();
        if (v < 1 || v > 125) {
            log.warn("Strategy {} leverage param out of range: {} — ignoring", signal.strategyId(), lev);
            return null;
        }
        return v;
    }

    private String strategyParam(TradingSignalPayload signal, String key) {
        if (signal.strategyId() == null) {
            return null;
        }
        Optional<String> value = configCache.getStrategyParam(signal.strategyId(), key);
        return value == null ? null : value.orElse(null); // null-guard: mock in unit tests
    }

    private BigDecimal strategyParamDecimal(TradingSignalPayload signal, String key) {
        String raw = strategyParam(signal, key);
        if (raw == null) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("Strategy {} param {} is not numeric: '{}' — ignoring", signal.strategyId(), key, raw);
            return null;
        }
    }

    /** Parses a decimal string from a signal (not a strategy param); null on an empty/invalid string. */
    private static BigDecimal parsePrice(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Rounds the quantity DOWN to the lot step (a partial leg must not exceed the position). */
    private BigDecimal roundToStepSize(String venue, String symbol, BigDecimal qty) {
        BigDecimal step = configCache.getSymbolRules(venue, symbol).stepSize();
        if (step == null || step.signum() <= 0) {
            return qty;
        }
        return qty.divide(step, 0, RoundingMode.DOWN).multiply(step);
    }

    private BigDecimal applyTickOffset(String venue, String symbol, BigDecimal price, int ticks) {
        if (ticks == 0) return price;
        BigDecimal tickSize = tickSizeFor(venue, symbol);
        return price.add(tickSize.multiply(BigDecimal.valueOf(ticks)));
    }

    private BigDecimal roundToTickSize(String venue, String symbol, BigDecimal price) {
        BigDecimal tickSize = tickSizeFor(venue, symbol);
        if (tickSize.compareTo(BigDecimal.ONE) == 0) return price.setScale(0, RoundingMode.HALF_UP);
        return price.divide(tickSize, 0, RoundingMode.HALF_UP).multiply(tickSize);
    }

    /**
     * Tick size from the DB-backed {@link ConfigCache} (ADR-0020 authority, synced from the exchange
     * contracts API), the same source {@code QuantityNormalizer} uses for step size. Symbols added at
     * runtime through the admin panel exist only in the DB, not in static {@code application.yml} — the
     * old yml lookup fell back to {@code 1.0} for them and rounded TP/SL prices to garbage integers.
     * {@link ConfigCache#getSymbolRules} never returns null (falls back to {@code SymbolRules.DEFAULT},
     * tickSize {@code 0.01}).
     *
     * <p>Since V10, specs are taken from the execution exchange: the price step for the same ticker differs between BingX and Binance
     * and TP/SL rounding must be computed using the one the order goes to.
     */
    private BigDecimal tickSizeFor(String venue, String symbol) {
        return configCache.getSymbolRules(venue, symbol).tickSize();
    }
}
