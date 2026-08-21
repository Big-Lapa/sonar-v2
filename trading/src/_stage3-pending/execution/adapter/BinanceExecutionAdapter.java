package com.sonar.trading.execution.adapter;

import com.sonar.trading.exchange.binance.BinanceOrderMapper;
import com.sonar.trading.exchange.binance.BinanceOrderSide;
import com.sonar.trading.exchange.binance.BinanceOrderType;
import com.sonar.trading.exchange.binance.BinancePlaceOrderRequest;
import com.sonar.trading.exchange.binance.BinancePositionSide;
import com.sonar.trading.exchange.binance.BinanceTimeInForce;
import com.sonar.trading.exchange.dto.AdapterOrderRequest;
import com.sonar.trading.exchange.dto.AdapterOrderResult;
import com.sonar.trading.exchange.model.AdapterOrderState;
import com.sonar.trading.exchange.model.AdapterOrderType;
import com.sonar.trading.exchange.model.OrderIntent;
import com.sonar.trading.exchange.spi.ExecutionAdapter;
import com.sonar.trading.execution.gateway.BinanceExecutionGateway;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Live execution adapter against Binance USDT-M perpetual futures (testnet) — mirror of
 * {@link LiveExecutionAdapter} (BingX). Created only when {@code trading.exchange=BINANCE}.
 *
 * <p>All REST traffic goes through {@link BinanceExecutionGateway} (retry, rate-limit, circuit
 * breaker, close&gt;open priority). One-Way Mode: every order carries {@code positionSide=BOTH};
 * CLOSE/REDUCE legs additionally set {@code reduceOnly=true} so they can only reduce exposure.
 *
 * <p>Scope: MARKET/LIMIT place, getOrder, cancel, {@link #closeAllPositions(String)} via
 * reduce-only MARKET. PR-D (volspike-pressure-v1): TP/SL attachment исполняется как ОТДЕЛЬНЫЕ
 * reduce-only условные ордера (STOP_MARKET + TAKE_PROFIT_MARKET, опц. частичная TP1-нога) —
 * у Binance нет attached-OCO как у BingX; плечо выставляется перед ENTRY per-strategy
 * параметром с кэшем по символу.
 */
@Component("binanceExecutionAdapter")
@ConditionalOnProperty(name = "trading.exchange", havingValue = "BINANCE")
public class BinanceExecutionAdapter implements ExecutionAdapter {

    private static final Logger log = LoggerFactory.getLogger(BinanceExecutionAdapter.class);

    /** TTL кэша неустранимого отказа setLeverage — не долбить /fapi/v1/leverage каждый вход. */
    private static final long LEVERAGE_FAILURE_TTL_MS = 10 * 60_000L;

    private final BinanceExecutionGateway gateway;
    private final com.sonar.trading.config.TradingProperties props;
    private final MeterRegistry meterRegistry;
    /** Тег account у tp_sl_leg_failed_total — иначе два Binance-аккаунта делят один счётчик
     *  и алерт не говорит, чья позиция осталась без стопа (ADR-0025, funding-counter паттерн). */
    private final String accountId;
    /** Последнее выставленное плечо per symbol — не дёргать /fapi/v1/leverage на каждый вход. */
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> leverageBySymbol =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** symbol → epoch-ms, до которого попытки setLeverage подавлены после отказа (ревью #88). */
    private final java.util.concurrent.ConcurrentHashMap<String, Long> leverageFailedUntil =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** tp_sl_leg_failed_total{symbol,leg} counters, per symbol+leg (funding-counter паттерн). */
    private final java.util.concurrent.ConcurrentHashMap<String, Counter> legFailureCounters =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Одно-аккаунтный путь (trading.exchange=BINANCE, бин): тот же DEFAULT_ACCOUNT, что и у
     *  {@code BinanceExecutionGatewayConfig}. Мульти-аккаунтный — через ctor с accountId. */
    @org.springframework.beans.factory.annotation.Autowired
    public BinanceExecutionAdapter(BinanceExecutionGateway gateway,
                                   com.sonar.trading.config.TradingProperties props,
                                   MeterRegistry meterRegistry) {
        this(gateway, props, meterRegistry,
                com.sonar.trading.health.SystemHealthService.DEFAULT_ACCOUNT);
    }

    public BinanceExecutionAdapter(BinanceExecutionGateway gateway,
                                   com.sonar.trading.config.TradingProperties props,
                                   MeterRegistry meterRegistry,
                                   String accountId) {
        this.gateway = gateway;
        this.props = props;
        this.meterRegistry = meterRegistry;
        this.accountId = accountId;
    }

    @Override
    public AdapterOrderResult placeOrder(AdapterOrderRequest request) {
        String binanceSymbol = BinanceOrderMapper.toBinanceSymbol(request.symbol());
        BinanceOrderSide side = BinanceOrderSide.valueOf(request.side().name());
        boolean limit = request.type() == AdapterOrderType.LIMIT;
        BinanceOrderType type = limit ? BinanceOrderType.LIMIT : BinanceOrderType.MARKET;

        // timeInForce: PostOnly → GTX (post-only); LIMIT default GTC; MARKET → null.
        BinanceTimeInForce tif = null;
        if (limit) {
            tif = "PostOnly".equalsIgnoreCase(request.timeInForce())
                    ? BinanceTimeInForce.GTX
                    : BinanceTimeInForce.GTC;
        }

        if (request.intent() == OrderIntent.ENTRY) {
            // Уборка ПЕРЕД входом: условные TP/SL ноги прошлого цикла (сработавший TP оставляет
            // SL в книге) иначе валидны против НОВОЙ позиции и закрывают её на чужом уровне —
            // зомби-стоп, CRITICAL ревью #88. DELETE allOpenOrders идемпотентен и дёшев.
            cancelAllOpenOrdersSafely(binanceSymbol, "pre-entry");
            ensureLeverage(binanceSymbol, request.leverage());
        }

        // CLOSE/REDUCE legs are reduce-only so they can never flip into an opposite position.
        boolean reduceOnly = request.intent() == OrderIntent.CLOSE_REDUCE;

        BinancePlaceOrderRequest order = new BinancePlaceOrderRequest(
                binanceSymbol,
                side,
                BinancePositionSide.BOTH,
                type,
                toPlain(request.quantity()),
                limit ? toPlain(request.price()) : null,
                tif,
                request.clientOrderId(),
                reduceOnly,
                null
        );

        log.info("[BINANCE] PLACE symbol={} side={} type={} qty={} cid={} intent={} reduceOnly={}",
                binanceSymbol, side, type, request.quantity(), request.clientOrderId(), request.intent(), reduceOnly);

        var resp = gateway.placeOrder(order, request.intent());

        String exchangeOrderId = BinanceOrderMapper.extractString(resp, "orderId", "data.orderId");
        String clientOrderId = BinanceOrderMapper.extractString(resp, "clientOrderId", "data.clientOrderId");
        if (clientOrderId == null) {
            clientOrderId = BinanceOrderMapper.clampClientOrderId(request.clientOrderId());
        }

        AdapterOrderResult placed = toResult(request.symbol(), exchangeOrderId, clientOrderId, resp);

        // Best-effort fill capture for market orders (Binance default response type is ACK/NEW).
        if (placed.state() == AdapterOrderState.NEW && exchangeOrderId != null) {
            try {
                var statusResp = gateway.getOrder(binanceSymbol, exchangeOrderId, clientOrderId);
                AdapterOrderResult refreshed = toResult(request.symbol(), exchangeOrderId, clientOrderId, statusResp);
                if (refreshed.state() != AdapterOrderState.NEW) {
                    placed = refreshed;
                }
            } catch (RuntimeException e) {
                log.warn("[BINANCE] post-place getOrder failed cid={} oid={}: {}",
                        clientOrderId, exchangeOrderId, e.getMessage());
            }
        }

        // Exchange-side TP/SL (PR-D): у Binance нет attached-OCO как у BingX — вешаем ОТДЕЛЬНЫЕ
        // условные ордера после входа. Best-effort: упавшая нога логируется, вход уже состоялся,
        // страховкой остаётся time-stop backstop + ручной closeall.
        if (request.tpSl() != null && request.intent() == OrderIntent.ENTRY
                && placed.state() != AdapterOrderState.REJECTED && placed.state() != AdapterOrderState.FAILED) {
            placeConditionalExits(binanceSymbol, side, request, placed);
        }
        return placed;
    }

    /**
     * Условные выходы противоположной стороной: полнообъёмные SL/TP идут с
     * {@code closePosition=true} — Binance сам снимает такой триггер при обнулении позиции и
     * при срабатывании парной ноги (OCO-эквивалент; на флэте триггер — no-op), объём не нужен,
     * dust-расхождений при частичном филле нет. Частичная TP1-нога ({@code partialTpEnabled}) —
     * reduce-only с объёмом от ФАКТИЧЕСКОГО филла (не запрошенного; ревью #88), её убирает
     * pre-entry/close-all cancelAllOpenOrders. workingType — из trading.tp-sl (BingX-путь его
     * всегда уважал, Binance молча брал last price — ревью #88).
     */
    private void placeConditionalExits(String binanceSymbol, BinanceOrderSide entrySide,
                                       AdapterOrderRequest request, AdapterOrderResult placed) {
        BinanceOrderSide exitSide = entrySide == BinanceOrderSide.BUY
                ? BinanceOrderSide.SELL : BinanceOrderSide.BUY;
        // Уровни якорятся на ФАКТИЧЕСКУЮ цену входа: пришедшие из ExecutionEngine посчитаны от
        // закрытия последней свечи (спот, до минуты давности), а исполняемся на перпе. Ноги
        // ставятся всё равно после ответа биржи, так что пересчёт не добавляет ни запроса, ни
        // задержки. Нет филла (ордер ещё NEW) — остаются исходные уровни, позиция без защиты не
        // остаётся.
        var tpSl = request.tpSl().rescaledTo(placed.avgPrice());
        if (tpSl != request.tpSl()) {
            log.info("[BINANCE] TP/SL пересчитаны от факт. входа {}: tp={} sl={} (было tp={} sl={})",
                    placed.avgPrice(), tpSl.takeProfitPrice(), tpSl.stopLossPrice(),
                    request.tpSl().takeProfitPrice(), request.tpSl().stopLossPrice());
        }
        String workingType = normalizedWorkingType();

        placeConditional(binanceSymbol, exitSide, BinanceOrderType.STOP_MARKET,
                null, true, tpSl.stopLossPrice(), workingType, "SL-" + shortId(), "SL");
        placeConditional(binanceSymbol, exitSide, BinanceOrderType.TAKE_PROFIT_MARKET,
                null, true, tpSl.takeProfitPrice(), workingType, "TP-" + shortId(), "TP");
        if (tpSl.hasPartial()) {
            BigDecimal filled = placed.filledQty();
            BigDecimal tp1Qty = tpSl.tp1Quantity();
            if (filled != null && filled.signum() > 0 && filled.compareTo(request.quantity()) < 0) {
                // Частичный филл входа: масштабируем ногу пропорционально, не больше филла.
                tp1Qty = tp1Qty.multiply(filled)
                        .divide(request.quantity(), 10, java.math.RoundingMode.DOWN);
            }
            if (filled == null || filled.signum() == 0) {
                log.warn("[BINANCE] partial TP1 skipped for {} — вход ещё без филла (state={}), "
                        + "объём ноги неизвестен", binanceSymbol, placed.state());
            } else if (tp1Qty.signum() > 0) {
                placeConditional(binanceSymbol, exitSide, BinanceOrderType.TAKE_PROFIT_MARKET,
                        toPlain(tp1Qty), false, tpSl.tp1Price(), workingType, "TP1-" + shortId(), "TP1");
            }
        }
    }

    private String normalizedWorkingType() {
        String wt = props.getTpSl().getWorkingType();
        if ("MARK_PRICE".equals(wt) || "CONTRACT_PRICE".equals(wt)) {
            return wt;
        }
        log.warn("[BINANCE] unknown trading.tp-sl.working-type '{}' — используем биржевой дефолт", wt);
        return null;
    }

    private void placeConditional(String binanceSymbol, BinanceOrderSide side, BinanceOrderType type,
                                  String quantity, boolean closePosition, BigDecimal stopPrice,
                                  String workingType, String cid, String leg) {
        if (stopPrice == null || (!closePosition && quantity == null)) {
            return;
        }
        try {
            BinancePlaceOrderRequest order = new BinancePlaceOrderRequest(
                    binanceSymbol, side, BinancePositionSide.BOTH, type,
                    quantity, null, null, cid, !closePosition, toPlain(stopPrice),
                    closePosition, workingType);
            log.info("[BINANCE] EXIT {} {} qty={} closePosition={} stopPrice={} cid={}",
                    type, binanceSymbol, quantity, closePosition, stopPrice, cid);
            gateway.placeOrder(order, OrderIntent.CLOSE_REDUCE);
        } catch (RuntimeException e) {
            log.error("[BINANCE] conditional exit {} for {} failed (позиция БЕЗ этой ноги защиты, "
                    + "time-stop остаётся страховкой): {}", type, binanceSymbol, e.getMessage());
            legFailureCounter(binanceSymbol, leg).increment();
        }
    }

    /** tp_sl_leg_failed_total{symbol,leg}: скрапится monitoring-service (TP_SL_LEG_FAILED алерт,
     *  см. docs/contracts/monitoring-alerts.md) — best-effort лог выше сам по себе незаметен. */
    private Counter legFailureCounter(String binanceSymbol, String leg) {
        return legFailureCounters.computeIfAbsent(binanceSymbol + '|' + leg,
                k -> Counter.builder("tp_sl_leg_failed_total")
                        .tag("account", accountId)
                        .tag("symbol", binanceSymbol)
                        .tag("leg", leg)
                        .description("Conditional TP/SL/TP1 exit leg rejected by the exchange after ENTRY "
                                + "(best-effort, position left without this exchange-side protection)")
                        .register(meterRegistry));
    }

    /** Снять все открытые ордера символа; отказ не блокирует основной путь (уборка best-effort,
     *  повторится на следующем входе/close-all). */
    private void cancelAllOpenOrdersSafely(String binanceSymbol, String reason) {
        try {
            gateway.cancelAllOpenOrders(binanceSymbol);
            log.info("[BINANCE] cancelAllOpenOrders {} ({})", binanceSymbol, reason);
        } catch (RuntimeException e) {
            log.warn("[BINANCE] cancelAllOpenOrders {} failed ({}): {}", binanceSymbol, reason, e.getMessage());
        }
    }

    /** Выставить плечо перед входом (per-strategy параметр), с кэшем по символу и TTL-кэшем
     *  отказа. Отказ не блокирует вход: торговать на текущем плече биржи безопаснее, чем не
     *  войти вовсе — нотионал фиксирован сайзингом, плечо влияет только на маржу. */
    private void ensureLeverage(String binanceSymbol, Integer leverage) {
        if (leverage == null || leverage.equals(leverageBySymbol.get(binanceSymbol))) {
            return;
        }
        Long failedUntil = leverageFailedUntil.get(binanceSymbol);
        if (failedUntil != null && failedUntil > System.currentTimeMillis()) {
            return; // недавний отказ (бракет/права ключа) — не долбим эндпоинт каждый вход
        }
        try {
            gateway.setLeverage(binanceSymbol, leverage);
            leverageBySymbol.put(binanceSymbol, leverage);
            leverageFailedUntil.remove(binanceSymbol);
            log.info("[BINANCE] leverage {}x set for {}", leverage, binanceSymbol);
        } catch (RuntimeException e) {
            leverageFailedUntil.put(binanceSymbol, System.currentTimeMillis() + LEVERAGE_FAILURE_TTL_MS);
            log.warn("[BINANCE] setLeverage {}x for {} failed (продолжаем на текущем плече биржи, "
                    + "ретрай через {} мин): {}", leverage, binanceSymbol,
                    LEVERAGE_FAILURE_TTL_MS / 60_000, e.getMessage());
        }
    }

    @Override
    public AdapterOrderResult getOrderStatus(String symbol, String clientOrderId) {
        String binanceSymbol = BinanceOrderMapper.toBinanceSymbol(symbol);
        var resp = gateway.getOrder(binanceSymbol, null, clientOrderId);
        String exchangeOrderId = BinanceOrderMapper.extractString(resp, "orderId", "data.orderId");
        return toResult(symbol, exchangeOrderId, clientOrderId, resp);
    }

    @Override
    public void cancelOrder(String symbol, String clientOrderId) {
        log.info("[BINANCE] CANCEL symbol={} cid={}", symbol, clientOrderId);
        gateway.cancelOrder(BinanceOrderMapper.toBinanceSymbol(symbol), null, clientOrderId);
    }

    /**
     * Force-close positions (kill-switch / FLIP). Binance has no bulk close endpoint, so query
     * {@code positionRisk} and submit a reduce-only MARKET order opposite each non-zero position.
     * A null symbol closes every open position.
     */
    @Override
    @SuppressWarnings("unchecked")
    public void closeAllPositions(String symbol) {
        String binanceSymbol = symbol == null ? null : BinanceOrderMapper.toBinanceSymbol(symbol);
        log.info("[BINANCE] CLOSE ALL POSITIONS symbol={}", binanceSymbol);

        // Сначала снять условные TP/SL ноги: reduce-only MARKET ниже обнулит позицию, а их
        // непогашенные триггеры остались бы жить против следующего входа (ревью #88).
        if (binanceSymbol != null) {
            cancelAllOpenOrdersSafely(binanceSymbol, "close-all");
        }

        var resp = gateway.getPositions(binanceSymbol);
        Object data = resp.get("data");
        if (!(data instanceof List<?> positions)) {
            log.warn("[BINANCE] closeAllPositions: unexpected positions payload for symbol={}", binanceSymbol);
            return;
        }

        for (Object p : positions) {
            if (!(p instanceof Map<?, ?> pos)) {
                continue;
            }
            Map<String, Object> posMap = (Map<String, Object>) pos;
            BigDecimal amt = BinanceOrderMapper.extractDecimal(posMap, "positionAmt");
            String posSymbol = BinanceOrderMapper.extractString(posMap, "symbol");
            if (amt == null || amt.signum() == 0 || posSymbol == null) {
                continue;
            }
            // Long (amt > 0) → SELL to close; Short (amt < 0) → BUY to close.
            BinanceOrderSide closeSide = amt.signum() > 0 ? BinanceOrderSide.SELL : BinanceOrderSide.BUY;
            String qty = amt.abs().stripTrailingZeros().toPlainString();
            String cid = BinanceOrderMapper.clampClientOrderId("CLS-" + shortId());

            BinancePlaceOrderRequest close = new BinancePlaceOrderRequest(
                    posSymbol, closeSide, BinancePositionSide.BOTH, BinanceOrderType.MARKET,
                    qty, null, null, cid, true, null);

            log.info("[BINANCE] closeAllPositions: {} {} qty={} (reduceOnly)", closeSide, posSymbol, qty);
            gateway.placeOrder(close, OrderIntent.CLOSE_REDUCE);
            if (binanceSymbol == null) {
                cancelAllOpenOrdersSafely(posSymbol, "close-all"); // per-symbol уборка при закрытии всего
            }
        }
    }

    @Override
    public String adapterName() {
        return "BINANCE";
    }

    private static AdapterOrderResult toResult(String coreSymbol, String exchangeOrderId,
                                               String clientOrderId, Map<String, Object> resp) {
        AdapterOrderState state = BinanceOrderMapper.mapState(
                BinanceOrderMapper.extractString(resp, "status", "data.status"));
        BigDecimal filledQty = BinanceOrderMapper.extractDecimal(resp, "executedQty", "data.executedQty");
        BigDecimal avgPrice = BinanceOrderMapper.extractDecimal(resp, "avgPrice", "data.avgPrice");
        // Binance does not return commission on the order response (it is on user trades); leave null.
        return new AdapterOrderResult(
                exchangeOrderId, clientOrderId, coreSymbol,
                state, filledQty, avgPrice, null, System.currentTimeMillis());
    }

    private static String toPlain(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros().toPlainString();
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
