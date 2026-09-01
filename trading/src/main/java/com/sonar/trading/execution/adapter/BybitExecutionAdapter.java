package com.sonar.trading.execution.adapter;

import com.sonar.trading.config.TradingProperties;
import com.sonar.trading.exchange.bybit.BybitFuturesClient;
import com.sonar.trading.exchange.bybit.BybitOrderMapper;
import com.sonar.trading.exchange.bybit.BybitOrderSide;
import com.sonar.trading.exchange.bybit.BybitOrderType;
import com.sonar.trading.exchange.bybit.BybitPlaceOrderRequest;
import com.sonar.trading.exchange.bybit.BybitTimeInForce;
import com.sonar.trading.exchange.bybit.BybitTriggerBy;
import com.sonar.trading.exchange.dto.AdapterOrderRequest;
import com.sonar.trading.exchange.dto.AdapterOrderResult;
import com.sonar.trading.exchange.dto.TpSlAttachment;
import com.sonar.trading.exchange.model.AdapterOrderSide;
import com.sonar.trading.exchange.model.AdapterOrderState;
import com.sonar.trading.exchange.model.AdapterOrderType;
import com.sonar.trading.exchange.model.OrderIntent;
import com.sonar.trading.exchange.spi.ExecutionAdapter;
import com.sonar.trading.execution.gateway.BybitExecutionGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Live execution adapter against Bybit V5 USDT-perpetual (testnet by default) — mirrors
 * {@link LiveExecutionAdapter} (BingX). Created only when {@code trading.exchange=LIVE}; the
 * default PAPER mode never instantiates it.
 *
 * <p>All Bybit REST traffic goes through {@link BybitExecutionGateway} (retries, rate limiting,
 * circuit breaker — same T16 machinery as BingX, separate registries per venue).
 *
 * <p>Position mode is one-way ({@code positionIdx=0}) everywhere (grilled Q2) — same as BingX's
 * {@code positionSide=BOTH}. The platform is currently LONG-only (ProposalExecutionService always
 * enters BUY), so every reduce-only leg here is a SELL, same assumption BingX's adapter makes.
 */
@Component("bybitExecutionAdapter")
@ConditionalOnProperty(name = "trading.exchange", havingValue = "LIVE")
public class BybitExecutionAdapter implements ExecutionAdapter {

    private static final Logger log = LoggerFactory.getLogger(BybitExecutionAdapter.class);

    private final BybitExecutionGateway gateway;
    private final TradingProperties props;
    private final BybitFuturesClient bybitFuturesClient;

    public BybitExecutionAdapter(BybitExecutionGateway gateway, TradingProperties props,
                                 BybitFuturesClient bybitFuturesClient) {
        this.gateway = gateway;
        this.props = props;
        this.bybitFuturesClient = bybitFuturesClient;
    }

    @Override
    public AdapterOrderResult placeOrder(AdapterOrderRequest request) {
        String symbol = BybitOrderMapper.toBybitSymbol(request.symbol());
        BybitOrderSide side = request.side() == AdapterOrderSide.BUY ? BybitOrderSide.Buy : BybitOrderSide.Sell;
        BybitOrderType type = request.type() == AdapterOrderType.LIMIT ? BybitOrderType.Limit : BybitOrderType.Market;
        boolean limit = type == BybitOrderType.Limit;
        BybitTimeInForce tif = request.timeInForce() != null
                ? BybitTimeInForce.valueOf(request.timeInForce())
                : (limit ? BybitTimeInForce.GTC : null);

        TpSlAttachment tpSlAtt = request.tpSl();
        BigDecimal tpPrice = null;
        BigDecimal slPrice = null;
        BybitTriggerBy triggerBy = null;
        if (tpSlAtt != null) {
            tpPrice = tpSlAtt.takeProfitPrice();
            slPrice = tpSlAtt.stopLossPrice();
            triggerBy = BybitTriggerBy.valueOf(props.getBybit().getTriggerBy());
            if (tpSlAtt.hasPartial()) {
                // Same gap as BingX's path (T23 partial leg) — documented there, not implemented here either.
                log.warn("[BYBIT] partial TP1 leg not supported on this venue — игнорируется");
            }
        }

        BybitPlaceOrderRequest order = new BybitPlaceOrderRequest(symbol, side, type,
                toPlain(request.quantity()), limit ? toPlain(request.price()) : null, tif,
                request.clientOrderId(), tpPrice, slPrice, triggerBy,
                request.intent() == OrderIntent.CLOSE_REDUCE);

        log.info("[LIVE] PLACE symbol={} side={} type={} qty={} cid={} intent={}",
                symbol, side, type, request.quantity(), request.clientOrderId(), request.intent());

        var resp = gateway.placeOrder(order, request.intent());

        String exchangeOrderId = BybitOrderMapper.extractString(resp, "orderId", "result.orderId");
        String clientOrderId = BybitOrderMapper.extractString(resp, "orderLinkId", "result.orderLinkId");
        if (clientOrderId == null) {
            clientOrderId = request.clientOrderId();
        }

        AdapterOrderResult placed = toResult(request.symbol(), exchangeOrderId, clientOrderId, resp);

        // Best-effort fill capture for market orders (full async handling parked, same as BingX path).
        if (placed.state() == AdapterOrderState.NEW && exchangeOrderId != null) {
            try {
                var statusResp = gateway.getOrder(symbol, exchangeOrderId, clientOrderId);
                AdapterOrderResult refreshed = toResult(request.symbol(), exchangeOrderId, clientOrderId, statusResp);
                if (refreshed.state() != AdapterOrderState.NEW) {
                    return refreshed;
                }
            } catch (RuntimeException e) {
                log.warn("[LIVE] post-place getOrder failed cid={} oid={}: {}",
                        clientOrderId, exchangeOrderId, e.getMessage());
            }
        }
        return placed;
    }

    @Override
    public AdapterOrderResult getOrderStatus(String symbol, String clientOrderId) {
        String bybitSymbol = BybitOrderMapper.toBybitSymbol(symbol);
        var resp = gateway.getOrder(bybitSymbol, null, clientOrderId);
        String exchangeOrderId = BybitOrderMapper.extractString(resp, "orderId", "result.orderId");
        return toResult(symbol, exchangeOrderId, clientOrderId, resp);
    }

    @Override
    public void cancelOrder(String symbol, String clientOrderId) {
        log.info("[LIVE] CANCEL symbol={} cid={}", symbol, clientOrderId);
        gateway.cancelOrder(BybitOrderMapper.toBybitSymbol(symbol), null, clientOrderId);
    }

    @Override
    public void closeAllPositions(String symbol) {
        log.info("[LIVE] CLOSE ALL POSITIONS symbol={}", symbol);
        gateway.closeAllPositions(symbol == null ? null : BybitOrderMapper.toBybitSymbol(symbol));
    }

    @Override
    public String adapterName() {
        return "BYBIT";
    }

    @Override
    public boolean hasOpenStopOrder(String symbol) {
        String bybitSymbol = BybitOrderMapper.toBybitSymbol(symbol);
        // TP/SL attached to the entry order (TpSlAttachment -> placeOrder's takeProfit/stopLoss
        // fields) become POSITION-level fields on Bybit, not separate orders — they never show up
        // in /v5/order/realtime until they trigger. Without this check, hasOpenStopOrder is a
        // false negative right after every entry and StopGuard places a duplicate standalone stop.
        // Retried, but ONLY while the position isn't visible at all: /v5/position/list can lag the
        // fill that just created it (same eventual consistency close-all has to wait out), and this
        // runs immediately after entry. A position that IS visible without a stop is a real answer
        // — nothing to wait for — so that case falls straight through to the order check.
        List<Map<String, Object>> rows = List.of();
        for (int attempt = 0; attempt < 3; attempt++) {
            if (attempt > 0) {
                sleep(500);
            }
            rows = BybitOrderMapper.resultList(bybitFuturesClient.getPositions(bybitSymbol));
            if (!rows.isEmpty()) {
                break;
            }
        }
        boolean positionLevelStop = rows.stream().anyMatch(row -> {
            String sl = BybitOrderMapper.extractString(row, "stopLoss");
            return sl != null && !sl.isBlank() && !"0".equals(sl);
        });
        if (positionLevelStop) {
            return true;
        }
        Map<String, Object> resp = bybitFuturesClient.getOpenOrders(bybitSymbol);
        return BybitOrderMapper.resultList(resp).stream().anyMatch(o -> matchesType(o, true));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Bybit-specific: deliberately does NOT clear the position-level TP/SL. The following
     * {@link #placeReduceOnlyStop} overwrites that slot atomically, so clearing it here would only
     * open a window in which the position has no protection — exactly what this venue's
     * trading-stop path exists to avoid. What this still cleans up is standalone conditional
     * orders, which the position-level slot cannot overwrite: leftovers from the older
     * cancel-then-place path, or orders placed by hand in the Bybit terminal.
     */
    @Override
    public void cancelReduceOnlyOrder(String symbol, boolean stopType) {
        String bybitSymbol = BybitOrderMapper.toBybitSymbol(symbol);
        Map<String, Object> resp = bybitFuturesClient.getOpenOrders(bybitSymbol);
        Optional<Map<String, Object>> existing = BybitOrderMapper.resultList(resp).stream()
                .filter(o -> matchesType(o, stopType))
                .findFirst();
        existing.ifPresent(o -> {
            String orderId = BybitOrderMapper.extractString(o, "orderId");
            if (orderId != null) {
                gateway.cancelOrder(bybitSymbol, orderId, null);
            }
        });
    }

    /**
     * {@inheritDoc}
     *
     * <p>Bybit-specific: this sets the POSITION-level stop-loss, the same slot the entry order's
     * attached SL lands in — so it REPLACES the existing stop rather than adding a second one.
     * That is the whole reason this venue doesn't cancel-then-place: an attached SL is not an
     * order, so no cancel could ever find it, and placing a standalone conditional alongside it
     * left the position carrying two stops at different prices with the older one firing first.
     *
     * <p>{@code qty} is ignored — {@code tpslMode=Full} always covers the whole position. Both
     * callers (StopGuard, PositionOpsService) pass the full position size anyway, so no behaviour
     * is lost; a genuinely partial stop would need {@code tpslMode=Partial}, which this venue's
     * partial-TP support (absent, see {@link #placeOrder}) doesn't warrant yet.
     */
    @Override
    public void placeReduceOnlyStop(String symbol, BigDecimal qty, BigDecimal stopPrice) {
        setPositionTpSl(symbol, toPlain(stopPrice), null);
    }

    /** {@inheritDoc} <p>Position-level take-profit — see {@link #placeReduceOnlyStop} for why. */
    @Override
    public void placeReduceOnlyTakeProfit(String symbol, BigDecimal qty, BigDecimal price) {
        setPositionTpSl(symbol, null, toPlain(price));
    }

    private void setPositionTpSl(String symbol, String stopLoss, String takeProfit) {
        String bybitSymbol = BybitOrderMapper.toBybitSymbol(symbol);
        BybitTriggerBy triggerBy = BybitTriggerBy.valueOf(props.getBybit().getTriggerBy());
        log.info("[LIVE] TRADING-STOP symbol={} sl={} tp={} triggerBy={}",
                bybitSymbol, stopLoss, takeProfit, triggerBy);
        gateway.setTradingStop(bybitSymbol, stopLoss, takeProfit, triggerBy);
    }

    @Override
    public BigDecimal getMarkPrice(String symbol) {
        String bybitSymbol = BybitOrderMapper.toBybitSymbol(symbol);
        Map<String, Object> resp = bybitFuturesClient.getPrice(bybitSymbol);
        List<Map<String, Object>> rows = BybitOrderMapper.resultList(resp);
        // markPrice first: conditional orders trigger against trading.bybit.trigger-by (MarkPrice
        // by default) — the drift gate should compare against the same price the exchange itself
        // uses to fire stops, not last-traded (which can gap further on thin books).
        BigDecimal fromList = rows.isEmpty() ? null : BybitOrderMapper.extractDecimal(rows.get(0), "markPrice", "lastPrice");
        return fromList != null ? fromList : BybitOrderMapper.extractDecimal(resp, "markPrice", "result.markPrice");
    }

    private static AdapterOrderResult toResult(String coreSymbol, String exchangeOrderId,
                                               String clientOrderId, Map<String, Object> resp) {
        // Bybit's order/create ack and order/realtime rows both carry these fields; realtime rows
        // arrive via result.list — extractString/extractDecimal fall back into result.list[0].
        AdapterOrderState state = BybitOrderMapper.mapState(BybitOrderMapper.extractString(resp,
                "orderStatus", "result.orderStatus"));
        BigDecimal filledQty = BybitOrderMapper.extractDecimal(resp, "cumExecQty", "result.cumExecQty");
        BigDecimal avgPrice = BybitOrderMapper.extractDecimal(resp, "avgPrice", "result.avgPrice");
        BigDecimal fee = BybitOrderMapper.extractDecimal(resp, "cumExecFee", "result.cumExecFee");
        return new AdapterOrderResult(
                exchangeOrderId, clientOrderId, coreSymbol,
                state, filledQty, avgPrice, fee, System.currentTimeMillis());
    }

    /**
     * Identifies a standalone conditional order — nowadays only leftovers from the older
     * cancel-then-place path and orders placed by hand in the Bybit terminal, since stops this
     * adapter sets go to the position-level slot instead.
     *
     * <p>Such an order comes back with {@code stopOrderType="Stop"} (generic) — NOT {@code
     * "StopLoss"}/{@code "TakeProfit"} (those values only appear on TP/SL set via {@code tpslMode}).
     * Matching on the {@code stopOrderType} substring therefore never found them — StopGuard
     * treated every one as missing and stacked a new stop on each poller tick.
     *
     * <p>Fix: identify ANY conditional order (non-blank {@code stopOrderType} or {@code
     * triggerPrice}), then classify stop vs. take-profit by {@code triggerDirection} — 2 (price
     * falls to trigger) for a stop on a long, 1 (price rises to trigger) for a take-profit. Falls
     * back to the {@code stopOrderType} substring only when {@code triggerDirection} is absent
     * (e.g. a conditional order placed by other tooling/{@code tpslMode}).
     */
    private static boolean matchesType(Map<String, Object> order, boolean wantsStop) {
        String stopOrderType = BybitOrderMapper.extractString(order, "stopOrderType");
        String triggerPrice = BybitOrderMapper.extractString(order, "triggerPrice");
        boolean isConditional = (stopOrderType != null && !stopOrderType.isBlank())
                || (triggerPrice != null && !triggerPrice.isBlank());
        if (!isConditional) {
            return false;
        }
        BigDecimal triggerDirection = BybitOrderMapper.extractDecimal(order, "triggerDirection");
        if (triggerDirection != null) {
            int dir = triggerDirection.intValue();
            return wantsStop ? dir == 2 : dir == 1;
        }
        String type = stopOrderType == null ? "" : stopOrderType.toUpperCase(Locale.ROOT);
        return wantsStop ? type.contains("STOPLOSS") || type.contains("STOP")
                : type.contains("TAKEPROFIT") || type.contains("PROFIT");
    }

    private static String toPlain(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros().toPlainString();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
