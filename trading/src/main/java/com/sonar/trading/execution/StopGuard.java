package com.sonar.trading.execution;

import com.sonar.trading.exchange.bingx.BingxFuturesClient;
import com.sonar.trading.exchange.bingx.BingxOrderMapper;
import com.sonar.trading.exchange.bingx.BingxOrderSide;
import com.sonar.trading.exchange.bingx.BingxOrderType;
import com.sonar.trading.exchange.bingx.BingxPlaceOrderRequest;
import com.sonar.trading.exchange.bingx.BingxPositionSide;
import com.sonar.trading.exchange.model.OrderIntent;
import com.sonar.trading.execution.gateway.ExecutionGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The 3rd invariant, enforced twice: right after an entry fills (4.6 step 5) and again every
 * minute for every open position (4.7) — a position must never sit without a reduce-only stop on
 * the exchange. One remediation attempt per check; a still-missing stop after that is the
 * poller's job to escalate loudly (Telegram, stage 6), not to retry silently forever.
 */
@Component
public class StopGuard {

    private static final Logger log = LoggerFactory.getLogger(StopGuard.class);

    private final BingxFuturesClient bingxFuturesClient;
    private final ExecutionGateway executionGateway;

    public StopGuard(BingxFuturesClient bingxFuturesClient, ExecutionGateway executionGateway) {
        this.bingxFuturesClient = bingxFuturesClient;
        this.executionGateway = executionGateway;
    }

    /** True if a stop was already present, or the one remediation attempt placed one successfully. */
    public boolean verifyAndRepair(String coreSymbol, BigDecimal qty, BigDecimal slPrice) {
        String bingxSymbol = BingxOrderMapper.toBingxSymbol(coreSymbol);
        try {
            if (hasStopOrder(bingxSymbol)) {
                return true;
            }
            log.error("[STOP-MISSING] no conditional stop found for {} — placing remediation order", bingxSymbol);
            BingxPlaceOrderRequest remediation = new BingxPlaceOrderRequest(bingxSymbol, BingxOrderSide.SELL,
                    BingxPositionSide.BOTH, BingxOrderType.STOP_MARKET, qty.stripTrailingZeros().toPlainString(),
                    null, null, shortClientOrderId("sr"), null, null,
                    slPrice.stripTrailingZeros().toPlainString(), true);
            executionGateway.placeOrder(remediation, OrderIntent.CLOSE_REDUCE);
            return hasStopOrder(bingxSymbol);
        } catch (RuntimeException e) {
            log.error("[STOP-MISSING] remediation failed for {}: {}", bingxSymbol, e.toString());
            return false;
        }
    }

    /** Read-only check, no remediation — used by the poller to decide whether to even call {@link #verifyAndRepair}. */
    @SuppressWarnings("unchecked")
    public boolean hasStopOrder(String bingxSymbol) {
        Map<String, Object> resp = bingxFuturesClient.getOpenOrders(bingxSymbol);
        Object data = resp.get("data");
        List<Map<String, Object>> orders;
        if (data instanceof List<?> list) {
            orders = (List<Map<String, Object>>) list;
        } else if (data instanceof Map<?, ?> m && m.get("orders") instanceof List<?> list) {
            orders = (List<Map<String, Object>>) list;
        } else {
            return false;
        }
        return orders.stream().anyMatch(o -> {
            String type = String.valueOf(o.getOrDefault("type", "")).toUpperCase();
            return type.contains("STOP");
        });
    }

    /** BingX rejects clientOrderId over 40 chars — a raw UUID (36) plus any prefix already blows it. */
    private static String shortClientOrderId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }
}
