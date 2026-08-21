package com.sonar.trading.execution.adapter;

import com.sonar.trading.config.TradingProperties;
import com.sonar.trading.exchange.bingx.BingxOrderMapper;
import com.sonar.trading.exchange.bingx.BingxOrderSide;
import com.sonar.trading.exchange.bingx.BingxOrderType;
import com.sonar.trading.exchange.bingx.BingxPlaceOrderRequest;
import com.sonar.trading.exchange.bingx.BingxPositionSide;
import com.sonar.trading.exchange.bingx.BingxTimeInForce;
import com.sonar.trading.exchange.bingx.BingxTpSlOrder;
import com.sonar.trading.exchange.bingx.BingxWorkingType;
import com.sonar.trading.exchange.dto.AdapterOrderRequest;
import com.sonar.trading.exchange.dto.AdapterOrderResult;
import com.sonar.trading.exchange.dto.TpSlAttachment;
import com.sonar.trading.exchange.model.AdapterOrderState;
import com.sonar.trading.exchange.model.AdapterOrderType;
import com.sonar.trading.exchange.model.OrderIntent;
import com.sonar.trading.exchange.spi.ExecutionAdapter;
import com.sonar.trading.execution.gateway.ExecutionGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Live execution adapter against BingX USDT-M perpetual futures (Swap V2, VST demo) — T13.
 *
 * <p>Translates platform {@link AdapterOrderRequest}s into BingX orders via {@link BingxFuturesClient}
 * and maps responses back through {@link BingxOrderMapper}. Created only when {@code trading.exchange=LIVE};
 * the default PAPER mode never instantiates it, so Phase 1 is untouched.
 *
 * <p>All BingX REST traffic goes through the {@link ExecutionGateway} (T16), which adds idempotent
 * retries, rate limiting and a circuit breaker with close&gt;open priority. The adapter passes the
 * order {@link OrderIntent} so the gateway can apply that priority.
 *
 * <p>Limitation (deferred to T17): for a MARKET order BingX typically acknowledges with a NEW
 * order id and the fill settles asynchronously. This adapter does a single best-effort {@code getOrder}
 * follow-up to capture an immediate fill; if the order is not yet terminal, the result stays NEW and
 * fill confirmation is left to the reconciliation worker.
 */
@Component("liveExecutionAdapter")
@ConditionalOnProperty(name = "trading.exchange", havingValue = "LIVE")
public class LiveExecutionAdapter implements ExecutionAdapter {

    private static final Logger log = LoggerFactory.getLogger(LiveExecutionAdapter.class);

    private final ExecutionGateway gateway;
    private final TradingProperties props;

    public LiveExecutionAdapter(ExecutionGateway gateway, TradingProperties props) {
        this.gateway = gateway;
        this.props = props;
    }

    @Override
    public AdapterOrderResult placeOrder(AdapterOrderRequest request) {
        String bingxSymbol = BingxOrderMapper.toBingxSymbol(request.symbol());
        BingxOrderSide side = BingxOrderSide.valueOf(request.side().name());
        BingxOrderType type = BingxOrderType.valueOf(request.type().name());
        boolean limit = request.type() == AdapterOrderType.LIMIT;
        // timeInForce: explicit request value (e.g. PostOnly) wins; LIMIT default = GTC; MARKET = null.
        BingxTimeInForce tif = request.timeInForce() != null
                ? BingxTimeInForce.valueOf(request.timeInForce())
                : (limit ? BingxTimeInForce.GTC : null);

        // TP/SL attached (T23, OCO): only for ENTRY orders. workingType parsed lazily — avoids
        // IllegalArgumentException on invalid config when TP/SL is disabled (tpSl == null).
        TpSlAttachment tpSlAtt = request.tpSl();
        BingxTpSlOrder tpOrder = null;
        BingxTpSlOrder slOrder = null;
        if (tpSlAtt != null) {
            BingxWorkingType workingType = BingxWorkingType.valueOf(props.getTpSl().getWorkingType());
            tpOrder = BingxTpSlOrder.of(BingxOrderType.TAKE_PROFIT, tpSlAtt.takeProfitPrice(), workingType);
            slOrder = BingxTpSlOrder.of(BingxOrderType.STOP_MARKET, tpSlAtt.stopLossPrice(), workingType);
            if (tpSlAtt.hasPartial()) {
                // BingX-путь частичную ногу не реализует — при фан-ауте на два венью объёмы
                // разойдутся молча, если не сказать об этом вслух (ревью #88).
                log.warn("[BINGX] partial TP1 leg not supported on this venue — игнорируется "
                        + "(Binance-аккаунты ногу ставят)");
            }
        }

        BingxPlaceOrderRequest order = new BingxPlaceOrderRequest(
                bingxSymbol,
                side,
                BingxPositionSide.BOTH, // one-way mode: open/close both carry BOTH (BUG-1/T22)
                type,
                toPlain(request.quantity()),
                limit ? toPlain(request.price()) : null,
                tif,
                request.clientOrderId(),
                tpOrder,
                slOrder,
                // CLOSE/REDUCE legs must never flip through zero into an opposite position
                // (concurrent analyzer CLOSE vs operator close-all would net into a short).
                request.intent() == OrderIntent.CLOSE_REDUCE
        );

        log.info("[LIVE] PLACE symbol={} side={} type={} qty={} cid={} intent={}",
                bingxSymbol, side, type, request.quantity(), request.clientOrderId(), request.intent());

        var resp = gateway.placeOrder(order, request.intent());

        String exchangeOrderId = BingxOrderMapper.extractString(resp,
                "orderId", "data.orderId", "order.orderId", "data.order.orderId", "id", "data.id");
        String clientOrderId = BingxOrderMapper.extractString(resp,
                "clientOrderId", "data.clientOrderId", "order.clientOrderId", "data.order.clientOrderId");
        if (clientOrderId == null) {
            clientOrderId = request.clientOrderId();
        }

        AdapterOrderResult placed = toResult(request.symbol(), exchangeOrderId, clientOrderId, resp);

        // Best-effort fill capture for market orders (full async handling is T17).
        if (placed.state() == AdapterOrderState.NEW && exchangeOrderId != null) {
            try {
                var statusResp = gateway.getOrder(bingxSymbol, exchangeOrderId, clientOrderId);
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
        String bingxSymbol = BingxOrderMapper.toBingxSymbol(symbol);
        var resp = gateway.getOrder(bingxSymbol, null, clientOrderId);
        String exchangeOrderId = BingxOrderMapper.extractString(resp,
                "orderId", "data.orderId", "order.orderId", "data.order.orderId");
        return toResult(symbol, exchangeOrderId, clientOrderId, resp);
    }

    @Override
    public void cancelOrder(String symbol, String clientOrderId) {
        log.info("[LIVE] CANCEL symbol={} cid={}", symbol, clientOrderId);
        gateway.cancelOrder(BingxOrderMapper.toBingxSymbol(symbol), null, clientOrderId);
    }

    @Override
    public void closeAllPositions(String symbol) {
        log.info("[LIVE] CLOSE ALL POSITIONS symbol={}", symbol);
        gateway.closeAllPositions(symbol == null ? null : BingxOrderMapper.toBingxSymbol(symbol));
    }

    @Override
    public String adapterName() {
        return "BINGX";
    }

    private static AdapterOrderResult toResult(String coreSymbol, String exchangeOrderId,
                                               String clientOrderId, java.util.Map<String, Object> resp) {
        AdapterOrderState state = BingxOrderMapper.mapState(BingxOrderMapper.extractString(resp,
                "status", "data.status", "order.status", "data.order.status"));
        BigDecimal filledQty = BingxOrderMapper.extractDecimal(resp,
                "executedQty", "data.executedQty", "order.executedQty", "data.order.executedQty", "executedQtyStr");
        BigDecimal avgPrice = BingxOrderMapper.extractDecimal(resp,
                "avgPrice", "data.avgPrice", "order.avgPrice", "data.order.avgPrice", "priceAvg");
        BigDecimal fee = BingxOrderMapper.extractDecimal(resp,
                "commission", "data.commission", "order.commission", "data.order.commission", "fee");
        return new AdapterOrderResult(
                exchangeOrderId, clientOrderId, coreSymbol,
                state, filledQty, avgPrice, fee, System.currentTimeMillis());
    }

    private static String toPlain(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros().toPlainString();
    }
}
