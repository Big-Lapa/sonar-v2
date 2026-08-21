package com.sonar.trading.execution;

import com.sonar.trading.domain.entity.PositionEntity;
import com.sonar.trading.domain.repository.PositionRepository;
import com.sonar.trading.exchange.bingx.BingxFuturesClient;
import com.sonar.trading.exchange.bingx.BingxOrderMapper;
import com.sonar.trading.exchange.bingx.BingxOrderSide;
import com.sonar.trading.exchange.bingx.BingxOrderType;
import com.sonar.trading.exchange.bingx.BingxPlaceOrderRequest;
import com.sonar.trading.exchange.bingx.BingxPositionSide;
import com.sonar.trading.exchange.model.OrderIntent;
import com.sonar.trading.exchange.spi.ExecutionAdapter;
import com.sonar.trading.execution.gateway.ExecutionGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** `/closeall`, `/be`, `/move` (4.7/5) — manual operator actions on an already-open position. */
@Service
public class PositionOpsService {

    private static final Logger log = LoggerFactory.getLogger(PositionOpsService.class);

    private final PositionRepository positionRepository;
    private final ExecutionAdapter liveExecutionAdapter;
    private final ExecutionGateway executionGateway;
    private final BingxFuturesClient bingxFuturesClient;

    public PositionOpsService(PositionRepository positionRepository, ExecutionAdapter liveExecutionAdapter,
                              ExecutionGateway executionGateway, BingxFuturesClient bingxFuturesClient) {
        this.positionRepository = positionRepository;
        this.liveExecutionAdapter = liveExecutionAdapter;
        this.executionGateway = executionGateway;
        this.bingxFuturesClient = bingxFuturesClient;
    }

    public sealed interface OpResult {
        record Ok(String message) implements OpResult {}
        record Failed(String reason) implements OpResult {}
    }

    /**
     * Reduce-only close of every open position for the account (optionally scoped to one symbol)
     * via BingX's native bulk close-all endpoint. DB status is NOT flipped here — the next minute
     * poller tick (≤60s) detects the close on the exchange and records it with PnL, same single
     * source of truth as an SL/TP-triggered close.
     */
    public OpResult closeAll(String accountId, String symbol) {
        List<PositionEntity> targets = symbol != null
                ? positionRepository.findByAccountIdAndSymbol(accountId, symbol).filter(p -> "OPEN".equals(p.getStatus()))
                        .map(List::of).orElse(List.of())
                : positionRepository.findAllOpenByAccountId(accountId);
        if (targets.isEmpty()) {
            return new OpResult.Failed("нет открытых позиций" + (symbol != null ? " по " + symbol : ""));
        }
        try {
            if (symbol != null) {
                liveExecutionAdapter.closeAllPositions(symbol);
            } else {
                for (PositionEntity p : targets) {
                    liveExecutionAdapter.closeAllPositions(p.getSymbol());
                }
            }
            return new OpResult.Ok("close-all отправлен для " + targets.size() + " позици(й), подтверждение придёт с поллером");
        } catch (RuntimeException e) {
            log.error("[close-all] {} failed: {}", accountId, e.toString());
            return new OpResult.Failed("ошибка close-all: " + e.getMessage());
        }
    }

    /** Cancel the old SL, place a new one at entry price, free the position's portfolio-risk slot (4.2). */
    public OpResult breakeven(Long positionId) {
        PositionEntity position = positionRepository.findById(positionId).orElse(null);
        if (position == null || !"OPEN".equals(position.getStatus())) {
            return new OpResult.Failed("позиция не найдена или не открыта");
        }
        return moveStopOrTp(position, "sl", position.getEntryPrice(), true);
    }

    /** Cancel the old SL or TP, place a new one at the given price. */
    public OpResult move(Long positionId, String type, BigDecimal price) {
        PositionEntity position = positionRepository.findById(positionId).orElse(null);
        if (position == null || !"OPEN".equals(position.getStatus())) {
            return new OpResult.Failed("позиция не найдена или не открыта");
        }
        if (!"sl".equalsIgnoreCase(type) && !"tp".equalsIgnoreCase(type)) {
            return new OpResult.Failed("type должен быть sl или tp");
        }
        return moveStopOrTp(position, type.toLowerCase(), price, false);
    }

    @SuppressWarnings("unchecked")
    private OpResult moveStopOrTp(PositionEntity position, String type, BigDecimal price, boolean isBreakeven) {
        String bingxSymbol = BingxOrderMapper.toBingxSymbol(position.getSymbol());
        boolean wantsSl = "sl".equals(type);
        try {
            Map<String, Object> resp = bingxFuturesClient.getOpenOrders(bingxSymbol);
            Object data = resp.get("data");
            List<Map<String, Object>> orders = data instanceof List<?> l ? (List<Map<String, Object>>) l
                    : data instanceof Map<?, ?> m && m.get("orders") instanceof List<?> l2
                            ? (List<Map<String, Object>>) l2 : List.of();

            Optional<Map<String, Object>> existing = orders.stream()
                    .filter(o -> matchesType(String.valueOf(o.getOrDefault("type", "")), wantsSl))
                    .findFirst();
            existing.ifPresent(o -> {
                String orderId = BingxOrderMapper.extractString(o, "orderId");
                if (orderId != null) {
                    bingxFuturesClient.cancelOrder(bingxSymbol, orderId, null);
                }
            });

            BingxPlaceOrderRequest request = new BingxPlaceOrderRequest(bingxSymbol, BingxOrderSide.SELL,
                    BingxPositionSide.BOTH, wantsSl ? BingxOrderType.STOP_MARKET : BingxOrderType.TAKE_PROFIT_MARKET,
                    position.getQuantity().stripTrailingZeros().toPlainString(), null, null,
                    shortClientOrderId("mv"), null, null, price.stripTrailingZeros().toPlainString(), true);
            executionGateway.placeOrder(request, OrderIntent.CLOSE_REDUCE);

            if (wantsSl) {
                position.setSlPrice(price.toPlainString());
                if (isBreakeven) {
                    position.setPlannedRiskAmount(null); // frees portfolio-risk headroom, 4.2
                }
            } else {
                position.setTpPrice(price.toPlainString());
            }
            positionRepository.save(position);

            return new OpResult.Ok((isBreakeven ? "стоп переставлен в безубыток " : (wantsSl ? "SL " : "TP ") + "переставлен на ")
                    + price);
        } catch (RuntimeException e) {
            log.error("[{}] move {} failed: {}", position.getAccountId(), type, e.toString());
            return new OpResult.Failed("ошибка перестановки: " + e.getMessage());
        }
    }

    private static boolean matchesType(String rawType, boolean wantsSl) {
        String t = rawType.toUpperCase();
        return wantsSl ? t.contains("STOP") : t.contains("TAKE_PROFIT");
    }

    /** BingX rejects clientOrderId over 40 chars — a raw UUID (36) plus any prefix already blows it. */
    private static String shortClientOrderId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }
}
