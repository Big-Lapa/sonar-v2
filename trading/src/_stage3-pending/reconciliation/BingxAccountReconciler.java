package com.sonar.trading.reconciliation;

import com.sonar.trading.domain.entity.OrderEntity;
import com.sonar.trading.exchange.bingx.BingxOrderMapper;
import com.sonar.trading.exchange.model.AdapterOrderState;
import com.sonar.trading.execution.gateway.ExchangeReadClient;
import com.sonar.trading.reconciliation.model.ExchangeBalance;
import com.sonar.trading.reconciliation.model.ExchangeOrderStatus;
import com.sonar.trading.reconciliation.model.ExchangePosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * BingX Swap V2 implementation of {@link AccountReconciler} (P4): the reads and parsing that
 * lived inside the pre-P4 single-account {@link ReconciliationService}, scoped to one account's
 * gateway. Symbols convert core↔BingX ({@code BTCUSDT}↔{@code BTC-USDT}); the equity asset is
 * per-account ({@code VST} on the VST demo, {@code USDT} on real).
 */
public class BingxAccountReconciler implements AccountReconciler {

    private static final Logger log = LoggerFactory.getLogger(BingxAccountReconciler.class);

    private final String accountId;
    private final ExchangeReadClient client;
    private final String equityAsset;

    public BingxAccountReconciler(String accountId, ExchangeReadClient client, String equityAsset) {
        this.accountId = accountId;
        this.client = client;
        this.equityAsset = equityAsset;
    }

    @Override
    public String accountId() {
        return accountId;
    }

    @Override
    public String exchange() {
        return "BINGX";
    }

    @Override
    public List<ExchangePosition> positions(String coreSymbolOrNull) {
        String bingxSymbol = coreSymbolOrNull == null
                ? null
                : BingxOrderMapper.toBingxSymbol(coreSymbolOrNull.toUpperCase(Locale.ROOT));
        return BingxAccountParser.parsePositions(client.getPositions(bingxSymbol));
    }

    @Override
    public Optional<ExchangeBalance> balance() {
        return BingxAccountParser.parseBalance(client.getBalance(), equityAsset);
    }

    @Override
    public ExchangeOrderStatus orderStatus(OrderEntity order) {
        String bingxSymbol = BingxOrderMapper.toBingxSymbol(order.getSymbol().toUpperCase(Locale.ROOT));
        try {
            Map<String, Object> resp = client.getOrder(bingxSymbol, order.getExchangeOrderId(), order.getClientOrderId());
            AdapterOrderState state = BingxOrderMapper.mapState(BingxOrderMapper.extractString(resp,
                    "status", "data.status", "order.status", "data.order.status"));
            BigDecimal filled = BingxOrderMapper.extractDecimal(resp,
                    "executedQty", "data.executedQty", "order.executedQty", "data.order.executedQty");
            BigDecimal avg = BingxOrderMapper.extractDecimal(resp,
                    "avgPrice", "data.avgPrice", "order.avgPrice", "data.order.avgPrice", "priceAvg");
            BigDecimal fee = BingxOrderMapper.extractDecimal(resp,
                    "commission", "data.commission", "order.commission", "data.order.commission", "fee");
            String exId = BingxOrderMapper.extractString(resp,
                    "orderId", "data.orderId", "order.orderId", "data.order.orderId");
            return new ExchangeOrderStatus(order.getClientOrderId(), exId, state, filled, avg, fee);
        } catch (com.sonar.trading.exchange.exception.ExchangeException e) {
            if (e.getError() != null && e.getError().isOrderNotFound()) {
                // The exchange has no such order (e.g. the place was rejected with 200/code!=0
                // before the requireOk guard existed, or it aged out). Resolve as terminal
                // REJECTED so the order stops being polled forever.
                log.warn("[RECON] order not found on exchange account={} cid={} sym={} → REJECTED",
                        accountId, order.getClientOrderId(), order.getSymbol());
                return new ExchangeOrderStatus(order.getClientOrderId(), order.getExchangeOrderId(),
                        AdapterOrderState.REJECTED, BigDecimal.ZERO, null, null);
            }
            log.warn("[RECON] getOrder failed account={} cid={} sym={}: {}",
                    accountId, order.getClientOrderId(), order.getSymbol(), e.getMessage());
            return null;
        } catch (RuntimeException e) {
            log.warn("[RECON] getOrder failed account={} cid={} sym={}: {}",
                    accountId, order.getClientOrderId(), order.getSymbol(), e.getMessage());
            return null;
        }
    }
}
