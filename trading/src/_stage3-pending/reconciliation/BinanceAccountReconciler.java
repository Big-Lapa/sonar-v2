package com.sonar.trading.reconciliation;

import com.sonar.trading.domain.entity.OrderEntity;
import com.sonar.trading.exchange.binance.BinanceOrderMapper;
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
 * Binance USDT-M Futures implementation of {@link AccountReconciler} (P4). Symbols are identity
 * (core form is already {@code BTCUSDT}); positions come from {@code /fapi/v2/positionRisk} and
 * balances from {@code /fapi/v2/balance} via {@link BinanceAccountParser}. Binance's order query
 * carries no commission field, so {@code fee} is null — the fills written by order reconciliation
 * simply have no fee, same as the adapter's own result mapping.
 */
public class BinanceAccountReconciler implements AccountReconciler {

    private static final Logger log = LoggerFactory.getLogger(BinanceAccountReconciler.class);

    private final String accountId;
    private final ExchangeReadClient client;
    private final String equityAsset;

    public BinanceAccountReconciler(String accountId, ExchangeReadClient client, String equityAsset) {
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
        return "BINANCE";
    }

    @Override
    public List<ExchangePosition> positions(String coreSymbolOrNull) {
        String binanceSymbol = coreSymbolOrNull == null
                ? null
                : BinanceOrderMapper.toBinanceSymbol(coreSymbolOrNull);
        return BinanceAccountParser.parsePositions(client.getPositions(binanceSymbol));
    }

    @Override
    public Optional<ExchangeBalance> balance() {
        return BinanceAccountParser.parseBalance(client.getBalance(), equityAsset);
    }

    @Override
    public ExchangeOrderStatus orderStatus(OrderEntity order) {
        String binanceSymbol = BinanceOrderMapper.toBinanceSymbol(order.getSymbol().toUpperCase(Locale.ROOT));
        try {
            Map<String, Object> resp = client.getOrder(binanceSymbol, order.getExchangeOrderId(), order.getClientOrderId());
            AdapterOrderState state = BinanceOrderMapper.mapState(
                    BinanceOrderMapper.extractString(resp, "status", "data.status"));
            BigDecimal filled = BinanceOrderMapper.extractDecimal(resp, "executedQty", "data.executedQty");
            BigDecimal avg = BinanceOrderMapper.extractDecimal(resp, "avgPrice", "data.avgPrice");
            String exId = BinanceOrderMapper.extractString(resp, "orderId", "data.orderId");
            return new ExchangeOrderStatus(order.getClientOrderId(), exId, state, filled, avg, null);
        } catch (RuntimeException e) {
            log.warn("[RECON] getOrder failed account={} cid={} sym={}: {}",
                    accountId, order.getClientOrderId(), order.getSymbol(), e.getMessage());
            return null;
        }
    }
}
