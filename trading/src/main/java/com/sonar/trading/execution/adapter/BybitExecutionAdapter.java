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
import java.util.UUID;

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
        Map<String, Object> resp = bybitFuturesClient.getOpenOrders(bybitSymbol);
        return BybitOrderMapper.resultList(resp).stream().anyMatch(o -> matchesType(o, true));
    }

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

    @Override
    public void placeReduceOnlyStop(String symbol, BigDecimal qty, BigDecimal stopPrice) {
        placeStandaloneConditional(symbol, qty, stopPrice, /* triggerDirection falls */ 2, "sr");
    }

    @Override
    public void placeReduceOnlyTakeProfit(String symbol, BigDecimal qty, BigDecimal price) {
        // Closing a LONG on take-profit: price must RISE to trigger (triggerDirection=1).
        placeStandaloneConditional(symbol, qty, price, 1, "mv");
    }

    private void placeStandaloneConditional(String symbol, BigDecimal qty, BigDecimal price,
                                            int triggerDirection, String clientOrderIdPrefix) {
        String bybitSymbol = BybitOrderMapper.toBybitSymbol(symbol);
        BybitTriggerBy triggerBy = BybitTriggerBy.valueOf(props.getBybit().getTriggerBy());
        // SELL — the platform is currently LONG-only, same assumption as BingX's adapter.
        BybitPlaceOrderRequest request = BybitPlaceOrderRequest.standaloneTrigger(bybitSymbol, BybitOrderSide.Sell,
                qty.stripTrailingZeros().toPlainString(), price.stripTrailingZeros().toPlainString(),
                triggerBy, triggerDirection, shortClientOrderId(clientOrderIdPrefix));
        gateway.placeOrder(request, OrderIntent.CLOSE_REDUCE);
    }

    @Override
    public BigDecimal getMarkPrice(String symbol) {
        String bybitSymbol = BybitOrderMapper.toBybitSymbol(symbol);
        Map<String, Object> resp = bybitFuturesClient.getPrice(bybitSymbol);
        List<Map<String, Object>> rows = BybitOrderMapper.resultList(resp);
        BigDecimal fromList = rows.isEmpty() ? null : BybitOrderMapper.extractDecimal(rows.get(0), "lastPrice", "markPrice");
        return fromList != null ? fromList : BybitOrderMapper.extractDecimal(resp, "lastPrice", "result.lastPrice");
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

    private static boolean matchesType(Map<String, Object> order, boolean wantsStop) {
        // Bybit V5 conditional orders carry stopOrderType (StopLoss/TakeProfit/PartialStopLoss/...);
        // plain limit/market legs have it blank.
        String stopOrderType = String.valueOf(order.getOrDefault("stopOrderType", "")).toUpperCase(Locale.ROOT);
        return wantsStop ? stopOrderType.contains("STOPLOSS") : stopOrderType.contains("TAKEPROFIT");
    }

    private static String toPlain(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros().toPlainString();
    }

    /** Keep client order ids well under Bybit's 36-char orderLinkId limit. */
    private static String shortClientOrderId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }
}
