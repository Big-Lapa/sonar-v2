package com.sonar.trading.execution.adapter;

import com.sonar.trading.config.TradingProperties;
import com.sonar.trading.exchange.binance.BinanceFuturesClient;
import com.sonar.trading.exchange.binance.BinanceOrderMapper;
import com.sonar.trading.exchange.binance.BinanceOrderSide;
import com.sonar.trading.exchange.binance.BinanceOrderType;
import com.sonar.trading.exchange.binance.BinancePlaceOrderRequest;
import com.sonar.trading.exchange.binance.BinancePositionSide;
import com.sonar.trading.exchange.binance.BinanceTimeInForce;
import com.sonar.trading.exchange.dto.AdapterOpenOrder;
import com.sonar.trading.exchange.dto.AdapterOrderRequest;
import com.sonar.trading.exchange.dto.AdapterOrderResult;
import com.sonar.trading.exchange.dto.StopInspection;
import com.sonar.trading.exchange.dto.TpSlAttachment;
import com.sonar.trading.exchange.model.AdapterOrderState;
import com.sonar.trading.exchange.model.AdapterOrderType;
import com.sonar.trading.exchange.model.OrderIntent;
import com.sonar.trading.exchange.spi.ExecutionAdapter;
import com.sonar.trading.execution.gateway.BinanceExecutionGateway;
import com.sonar.trading.sizing.QuantityNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live execution adapter against Binance USDT-M perpetual futures (testnet by default) — mirrors
 * {@link com.sonar.trading.execution.adapter.BybitExecutionAdapter} (structure/per-account wiring)
 * and {@link LiveExecutionAdapter} (BingX — protection model: separate reduce-only conditional
 * orders, since Binance, like BingX, has no attach-to-entry TP/SL).
 *
 * <p>All Binance REST traffic goes through {@link BinanceExecutionGateway} (retries, rate
 * limiting, circuit breaker — same T16 machinery as the other venues, separate registries per
 * account). One-Way Mode: every order carries {@code positionSide=BOTH}; CLOSE/REDUCE legs
 * additionally set {@code reduceOnly=true}. The platform is currently LONG-only (ProposalExecutionService
 * always enters BUY), so every reduce-only leg here is a SELL — same assumption Bybit/BingX make.
 *
 * <p>Protection (StopGuard / PositionOpsService remediation) is a standalone
 * {@code STOP_MARKET}/{@code TAKE_PROFIT_MARKET} order with {@code closePosition=true}: Binance
 * closes the entire position when it triggers and cancels the sibling leg itself, so this is the
 * OCO-equivalent without a native OCO endpoint, and immune to qty drift from partial fills.
 */
public class BinanceExecutionAdapter implements ExecutionAdapter {

    private static final Logger log = LoggerFactory.getLogger(BinanceExecutionAdapter.class);

    private final BinanceExecutionGateway gateway;
    private final TradingProperties props;
    private final BinanceFuturesClient binanceFuturesClient;
    private final QuantityNormalizer quantityNormalizer;

    /** One instance per account — see {@link LiveExecutionAdapter}'s note on why. */
    private final String accountId;

    /** Last leverage set per symbol — avoids calling {@code /fapi/v1/leverage} on every entry. */
    private final ConcurrentHashMap<String, Integer> leverageBySymbol = new ConcurrentHashMap<>();

    public BinanceExecutionAdapter(String accountId, BinanceExecutionGateway gateway, TradingProperties props,
                                   BinanceFuturesClient binanceFuturesClient, QuantityNormalizer quantityNormalizer) {
        this.accountId = accountId;
        this.gateway = gateway;
        this.props = props;
        this.binanceFuturesClient = binanceFuturesClient;
        this.quantityNormalizer = quantityNormalizer;
    }

    @Override
    public AdapterOrderResult placeOrder(AdapterOrderRequest request) {
        String symbol = BinanceOrderMapper.toBinanceSymbol(request.symbol());

        if (request.intent() == OrderIntent.ENTRY) {
            // Leftover conditional TP/SL legs from a previous cycle (a triggered TP leaves its
            // sibling SL resting) are otherwise still valid against the NEW position and would
            // close it at a stale level — swept before every entry, same as BingX/Bybit clean up
            // their own stale conditionals via cancelReduceOnlyOrder's callers.
            //
            // Only when the symbol is currently FLAT: a second entry on an already-open position
            // (pyramiding, or a retried idea for the same symbol) would otherwise sweep away that
            // position's own live protection before the new order even reaches the exchange — and
            // a subsequent placeOrder failure (rate limit, margin, open breaker) then leaves it
            // unprotected with nothing but the time-stop backstop. hasOpenPosition fails safe: a
            // failed read is treated as "assume a position exists", skipping the sweep rather than
            // risking it.
            if (hasOpenPosition(symbol)) {
                log.info("[BINANCE] {} already has an open position — skipping pre-entry conditional-order "
                        + "sweep so its existing protection is left alone", symbol);
            } else {
                cancelAllOpenOrdersSafely(symbol, "pre-entry");
            }
            if (request.leverage() != null) {
                ensureLeverage(symbol, request.leverage());
            }
        }

        BinanceOrderSide side = BinanceOrderSide.valueOf(request.side().name());
        boolean limit = request.type() == AdapterOrderType.LIMIT;
        BinanceOrderType type = limit ? BinanceOrderType.LIMIT : BinanceOrderType.MARKET;

        BinanceTimeInForce tif = null;
        if (limit) {
            tif = "PostOnly".equalsIgnoreCase(request.timeInForce()) ? BinanceTimeInForce.GTX : BinanceTimeInForce.GTC;
        }

        boolean reduceOnly = request.intent() == OrderIntent.CLOSE_REDUCE;

        BinancePlaceOrderRequest order = new BinancePlaceOrderRequest(
                symbol, side, BinancePositionSide.BOTH, type,
                toPlain(request.quantity()), limit ? toPlain(request.price()) : null, tif,
                request.clientOrderId(), reduceOnly);

        log.info("[LIVE] PLACE symbol={} side={} type={} qty={} cid={} intent={}",
                symbol, side, type, request.quantity(), request.clientOrderId(), request.intent());

        var resp = gateway.placeOrder(order, request.intent());

        String exchangeOrderId = BinanceOrderMapper.extractString(resp, "orderId");
        String clientOrderId = BinanceOrderMapper.extractString(resp, "clientOrderId");
        if (clientOrderId == null) {
            clientOrderId = BinanceOrderMapper.clampClientOrderId(request.clientOrderId());
        }

        AdapterOrderResult placed = toResult(request.symbol(), exchangeOrderId, clientOrderId, resp);

        // Best-effort fill capture for market orders (Binance's default place response is ACK/NEW).
        if (placed.state() == AdapterOrderState.NEW && exchangeOrderId != null) {
            try {
                var statusResp = gateway.getOrder(symbol, exchangeOrderId, clientOrderId);
                AdapterOrderResult refreshed = toResult(request.symbol(), exchangeOrderId, clientOrderId, statusResp);
                if (refreshed.state() != AdapterOrderState.NEW) {
                    placed = refreshed;
                }
            } catch (RuntimeException e) {
                log.warn("[LIVE] post-place getOrder failed cid={} oid={}: {}",
                        clientOrderId, exchangeOrderId, e.getMessage());
            }
        }

        if (request.tpSl() != null && request.intent() == OrderIntent.ENTRY
                && placed.state() != AdapterOrderState.REJECTED && placed.state() != AdapterOrderState.FAILED) {
            placeConditionalExits(symbol, side, request.quantity(), request.tpSl(), placed);
        }
        return placed;
    }

    /**
     * Standalone reduce-only exits, opposite side of the entry — Binance's OCO-equivalent (see
     * class doc). Levels are rescaled to the actual fill price when known ({@link
     * TpSlAttachment#rescaledTo}): unlike BingX's attached OCO (which only ever knows the
     * pre-fill reference price), these orders go out AFTER the entry response, so the real avgPrice
     * is already available and the strategy's intended percentages can be preserved exactly.
     */
    private void placeConditionalExits(String symbol, BinanceOrderSide entrySide, BigDecimal requestedQty,
                                       TpSlAttachment requested, AdapterOrderResult placed) {
        BinanceOrderSide exitSide = entrySide == BinanceOrderSide.BUY ? BinanceOrderSide.SELL : BinanceOrderSide.BUY;
        TpSlAttachment tpSl = requested.rescaledTo(placed.avgPrice());
        String workingType = normalizedWorkingType();

        placeConditional(symbol, exitSide, BinanceOrderType.STOP_MARKET, tpSl.stopLossPrice(), workingType, "sl");
        placeConditional(symbol, exitSide, BinanceOrderType.TAKE_PROFIT_MARKET, tpSl.takeProfitPrice(), workingType, "tp");

        if (tpSl.hasPartial()) {
            placePartialTakeProfit(symbol, exitSide, tpSl, placed, requestedQty, workingType);
        }
    }

    /**
     * Partial TP1 as an explicit reduce-only {@code TAKE_PROFIT_MARKET} with a real quantity —
     * {@code closePosition=true} (used by the two full-size legs above) is not an option here
     * since it always closes the whole position, not a fraction of it. Scaled down proportionally
     * when the entry only partially filled, mirroring the earlier implementation this was
     * ported from.
     */
    private void placePartialTakeProfit(String symbol, BinanceOrderSide exitSide, TpSlAttachment tpSl,
                                        AdapterOrderResult placed, BigDecimal requestedQty, String workingType) {
        BigDecimal filled = placed.filledQty();
        if (filled == null || filled.signum() == 0) {
            log.warn("[BINANCE] partial TP1 skipped for {} — entry not filled yet (state={}), leg quantity unknown",
                    symbol, placed.state());
            return;
        }
        BigDecimal tp1Qty = tpSl.tp1Quantity();
        if (requestedQty != null && requestedQty.signum() > 0 && filled.compareTo(requestedQty) < 0) {
            tp1Qty = tp1Qty.multiply(filled).divide(requestedQty, 10, java.math.RoundingMode.DOWN);
        }
        if (tp1Qty == null || tp1Qty.signum() <= 0) {
            return;
        }
        // Full-size legs above carry no quantity at all (closePosition=true) — sizing upstream
        // never gets a chance to round THIS one, since it's computed here from the fill ratio.
        // Sent unrounded, a fraction like 0.09999 at a 0.001 stepSize comes back as exchange
        // rejection -1111 (precision) — the same "position ends up without this leg" outcome the
        // fill-scaling was trying to avoid, just through a venue error instead of a log line.
        // tp1Price() (not null) so floorForClose's minNotional check actually runs — a small
        // partial fill can shrink tp1Qty below step/minQty fine but still under the exchange's
        // MIN_NOTIONAL floor, which a null price would silently skip checking and let the leg go
        // out anyway, only to bounce back as -4164 (min notional) — the same round-trip this
        // rounding step exists to avoid.
        BigDecimal flooredQty = quantityNormalizer.floorForClose(accountId, symbol, tp1Qty, tpSl.tp1Price());
        if (flooredQty == null) {
            log.warn("[BINANCE] partial TP1 skipped for {} — scaled quantity {} floors to below the "
                    + "exchange's step/min size/min notional", symbol, tp1Qty);
            return;
        }
        tp1Qty = flooredQty;
        try {
            BinancePlaceOrderRequest order = new BinancePlaceOrderRequest(symbol, exitSide, BinancePositionSide.BOTH,
                    BinanceOrderType.TAKE_PROFIT_MARKET, toPlain(tp1Qty), null, null, shortClientOrderId("tp1"),
                    true, toPlain(tpSl.tp1Price()), false, workingType);
            log.info("[LIVE] EXIT TAKE_PROFIT_MARKET {} qty={} (partial TP1) stopPrice={} cid={}",
                    symbol, tp1Qty, tpSl.tp1Price(), order.newClientOrderId());
            gateway.placeOrder(order, OrderIntent.CLOSE_REDUCE);
        } catch (RuntimeException e) {
            log.error("[BINANCE] partial TP1 leg for {} failed (position left without this leg of protection): {}",
                    symbol, e.getMessage());
        }
    }

    private String normalizedWorkingType() {
        String wt = props.getTpSl().getWorkingType();
        if ("MARK_PRICE".equals(wt) || "CONTRACT_PRICE".equals(wt)) {
            return wt;
        }
        log.warn("[BINANCE] unknown trading.tp-sl.working-type '{}' — using exchange default", wt);
        return null;
    }

    private void placeConditional(String symbol, BinanceOrderSide side, BinanceOrderType type,
                                  BigDecimal stopPrice, String workingType, String leg) {
        if (stopPrice == null) {
            return;
        }
        try {
            BinancePlaceOrderRequest order = new BinancePlaceOrderRequest(symbol, side, BinancePositionSide.BOTH,
                    type, null, null, null, shortClientOrderId(leg), false, toPlain(stopPrice), true, workingType);
            log.info("[LIVE] EXIT {} {} closePosition=true stopPrice={} cid={}",
                    type, symbol, stopPrice, order.newClientOrderId());
            gateway.placeOrder(order, OrderIntent.CLOSE_REDUCE);
        } catch (RuntimeException e) {
            log.error("[BINANCE] conditional exit {} for {} failed (position left without this leg of "
                    + "protection; time-stop backstop remains): {}", type, symbol, e.getMessage());
        }
    }

    /** Best-effort; a failed sweep does not block the entry — it will be retried next entry/close-all. */
    private void cancelAllOpenOrdersSafely(String symbol, String reason) {
        try {
            gateway.cancelAllOpenOrders(symbol);
        } catch (RuntimeException e) {
            log.warn("[BINANCE] cancelAllOpenOrders {} failed ({}): {}", symbol, reason, e.getMessage());
        }
    }

    /**
     * Set leverage before ENTRY (per-strategy param), cached per symbol so a repeat entry at the
     * same leverage doesn't re-hit {@code /fapi/v1/leverage}. Failure does not block the entry:
     * trading at the exchange's current leverage is safer than not entering at all — notional is
     * fixed by sizing, leverage only changes margin usage.
     */
    private void ensureLeverage(String symbol, int leverage) {
        if (Integer.valueOf(leverage).equals(leverageBySymbol.get(symbol))) {
            return;
        }
        try {
            gateway.setLeverage(symbol, leverage);
            leverageBySymbol.put(symbol, leverage);
        } catch (RuntimeException e) {
            log.warn("[LIVE] setLeverage {}x failed for {}, proceeding with existing exchange leverage: {}",
                    leverage, symbol, e.toString());
        }
    }

    @Override
    public AdapterOrderResult getOrderStatus(String symbol, String clientOrderId) {
        String binanceSymbol = BinanceOrderMapper.toBinanceSymbol(symbol);
        var resp = gateway.getOrder(binanceSymbol, null, clientOrderId);
        String exchangeOrderId = BinanceOrderMapper.extractString(resp, "orderId");
        return toResult(symbol, exchangeOrderId, clientOrderId, resp);
    }

    @Override
    public void cancelOrder(String symbol, String clientOrderId) {
        log.info("[LIVE] CANCEL symbol={} cid={}", symbol, clientOrderId);
        gateway.cancelOrder(BinanceOrderMapper.toBinanceSymbol(symbol), null, clientOrderId);
    }

    /**
     * Binance has no bulk close-all endpoint (like BingX/Bybit's own synthesized paths): read
     * open positions and submit a reduce-only MARKET order opposite each non-flat one. Leftover
     * conditional TP/SL legs are swept first — otherwise they would keep resting after the
     * position they were protecting is gone, ready to fire against whatever opens next.
     */
    @Override
    public void closeAllPositions(String symbol) {
        String binanceSymbol = symbol == null ? null : BinanceOrderMapper.toBinanceSymbol(symbol);
        log.info("[LIVE] CLOSE ALL POSITIONS symbol={}", binanceSymbol);

        if (binanceSymbol != null) {
            cancelAllOpenOrdersSafely(binanceSymbol, "close-all");
        }

        for (Map<String, Object> row : BinanceOrderMapper.dataRows(gateway.getPositions(binanceSymbol))) {
            BigDecimal amt = BinanceOrderMapper.extractDecimal(row, "positionAmt");
            String rowSymbol = BinanceOrderMapper.extractString(row, "symbol");
            if (amt == null || amt.signum() == 0 || rowSymbol == null) {
                continue;
            }
            if (binanceSymbol == null) {
                cancelAllOpenOrdersSafely(rowSymbol, "close-all");
            }
            BinanceOrderSide closeSide = amt.signum() > 0 ? BinanceOrderSide.SELL : BinanceOrderSide.BUY;
            String qty = amt.abs().stripTrailingZeros().toPlainString();
            BinancePlaceOrderRequest close = new BinancePlaceOrderRequest(rowSymbol, closeSide,
                    BinancePositionSide.BOTH, BinanceOrderType.MARKET, qty, null, null,
                    shortClientOrderId("cls"), true);
            log.info("[LIVE] closeAllPositions: {} {} qty={} (reduceOnly)", closeSide, rowSymbol, qty);
            gateway.placeOrder(close, OrderIntent.CLOSE_REDUCE);
        }
    }

    @Override
    public String adapterName() {
        return "BINANCE:" + accountId;
    }

    /**
     * Binance's protective orders are ordinary reduce-only conditionals (STOP_MARKET /
     * TAKE_PROFIT_MARKET) that show up in {@code /fapi/v1/openOrders} the same as any other order
     * (unlike Bybit, where an attached stop is a position field invisible here) — mirrors
     * {@link LiveExecutionAdapter#hasOpenStopOrder} (BingX) almost exactly.
     */
    @Override
    public boolean hasOpenStopOrder(String symbol) {
        String binanceSymbol = BinanceOrderMapper.toBinanceSymbol(symbol);
        return openOrderRows(binanceSymbol).stream().anyMatch(o -> matchesType(o, true));
    }

    @Override
    public StopInspection inspectStops(String symbol) {
        String binanceSymbol = BinanceOrderMapper.toBinanceSymbol(symbol);
        BigDecimal sl = null;
        BigDecimal tp = null;
        boolean hasStop = false;
        for (Map<String, Object> o : openOrderRows(binanceSymbol)) {
            boolean isStop = matchesType(o, true);
            boolean isTakeProfit = !isStop && matchesType(o, false);
            BigDecimal level = BinanceOrderMapper.extractDecimal(o, "stopPrice");
            if (isStop) {
                hasStop = true;
                if (level != null && level.signum() != 0) {
                    sl = level;
                }
            } else if (isTakeProfit && level != null && level.signum() != 0) {
                tp = level;
            }
        }
        return new StopInspection(hasStop, sl, tp);
    }

    @Override
    public List<AdapterOpenOrder> listOpenOrders(String symbol) {
        String binanceSymbol = BinanceOrderMapper.toBinanceSymbol(symbol);
        return openOrderRows(binanceSymbol).stream().map(o -> new AdapterOpenOrder(
                BinanceOrderMapper.extractString(o, "orderId"),
                BinanceOrderMapper.extractString(o, "clientOrderId"),
                symbol,
                BinanceOrderMapper.extractString(o, "side"),
                BinanceOrderMapper.extractString(o, "type"),
                BinanceOrderMapper.extractDecimal(o, "origQty"),
                BinanceOrderMapper.extractDecimal(o, "price"),
                BinanceOrderMapper.extractDecimal(o, "stopPrice"),
                BinanceOrderMapper.extractString(o, "status")
        )).toList();
    }

    @Override
    public void cancelOrderById(String symbol, String orderId) {
        log.info("[LIVE] CANCEL symbol={} orderId={}", symbol, orderId);
        gateway.cancelOrder(BinanceOrderMapper.toBinanceSymbol(symbol), orderId, null);
    }

    @Override
    public void cancelReduceOnlyOrder(String symbol, boolean stopType) {
        String binanceSymbol = BinanceOrderMapper.toBinanceSymbol(symbol);
        Optional<Map<String, Object>> existing = openOrderRows(binanceSymbol).stream()
                .filter(o -> matchesType(o, stopType))
                .findFirst();
        existing.ifPresent(o -> {
            String orderId = BinanceOrderMapper.extractString(o, "orderId");
            if (orderId != null) {
                gateway.cancelOrder(binanceSymbol, orderId, null);
            }
        });
    }

    @Override
    public void placeReduceOnlyStop(String symbol, BigDecimal qty, BigDecimal stopPrice) {
        placeStandaloneConditional(symbol, stopPrice, BinanceOrderType.STOP_MARKET, "sr");
    }

    @Override
    public void placeReduceOnlyTakeProfit(String symbol, BigDecimal qty, BigDecimal price) {
        placeStandaloneConditional(symbol, price, BinanceOrderType.TAKE_PROFIT_MARKET, "mv");
    }

    /**
     * {@code closePosition=true}, not an explicit-quantity reduce-only order: it closes whatever
     * the position actually is at trigger time, so it never drifts out of sync with the qty
     * StopGuard/PositionOpsService pass in and needs no separate cancel-then-place window.
     */
    private void placeStandaloneConditional(String symbol, BigDecimal price, BinanceOrderType type, String prefix) {
        String binanceSymbol = BinanceOrderMapper.toBinanceSymbol(symbol);
        // SELL — the platform is currently LONG-only (see class doc).
        BinancePlaceOrderRequest request = new BinancePlaceOrderRequest(binanceSymbol, BinanceOrderSide.SELL,
                BinancePositionSide.BOTH, type, null, null, null, shortClientOrderId(prefix),
                false, price.stripTrailingZeros().toPlainString(), true, normalizedWorkingType());
        gateway.placeOrder(request, OrderIntent.CLOSE_REDUCE);
    }

    @Override
    public BigDecimal getMarkPrice(String symbol) {
        String binanceSymbol = BinanceOrderMapper.toBinanceSymbol(symbol);
        Map<String, Object> resp = binanceFuturesClient.getMarkPrice(binanceSymbol);
        return BinanceOrderMapper.extractDecimal(resp, "markPrice");
    }

    private List<Map<String, Object>> openOrderRows(String binanceSymbol) {
        return BinanceOrderMapper.dataRows(binanceFuturesClient.getOpenOrders(binanceSymbol));
    }

    /**
     * True if the symbol currently carries a non-flat position on this account. Fails safe: a
     * failed read is treated as "a position might exist" (returns true) rather than risking the
     * pre-entry conditional-order sweep against protection this call couldn't actually verify is
     * absent.
     */
    private boolean hasOpenPosition(String binanceSymbol) {
        try {
            for (Map<String, Object> row : BinanceOrderMapper.dataRows(gateway.getPositions(binanceSymbol))) {
                BigDecimal amt = BinanceOrderMapper.extractDecimal(row, "positionAmt");
                if (amt != null && amt.signum() != 0) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException e) {
            log.warn("[BINANCE] pre-entry position check failed for {} — assuming a position exists and "
                    + "skipping the conditional-order sweep to be safe: {}", binanceSymbol, e.getMessage());
            return true;
        }
    }

    private static boolean matchesType(Map<String, Object> order, boolean wantsStop) {
        String type = BinanceOrderMapper.extractString(order, "type");
        if (type == null) {
            return false;
        }
        String t = type.toUpperCase(Locale.ROOT);
        return wantsStop ? t.contains("STOP") : (t.contains("TAKE") || t.contains("PROFIT"));
    }

    private static AdapterOrderResult toResult(String coreSymbol, String exchangeOrderId,
                                               String clientOrderId, Map<String, Object> resp) {
        AdapterOrderState state = BinanceOrderMapper.mapState(BinanceOrderMapper.extractString(resp, "status"));
        BigDecimal filledQty = BinanceOrderMapper.extractDecimal(resp, "executedQty");
        BigDecimal avgPrice = BinanceOrderMapper.extractDecimal(resp, "avgPrice");
        // Binance does not return commission on the order response (only on user trades) — left null.
        return new AdapterOrderResult(exchangeOrderId, clientOrderId, coreSymbol, state, filledQty, avgPrice, null,
                System.currentTimeMillis());
    }

    private static String toPlain(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros().toPlainString();
    }

    /** Binance rejects a clientOrderId over 36 chars — clamp keeps this well under that. */
    private static String shortClientOrderId(String prefix) {
        return BinanceOrderMapper.clampClientOrderId(prefix + "-" + UUID.randomUUID().toString().replace("-", ""));
    }
}
