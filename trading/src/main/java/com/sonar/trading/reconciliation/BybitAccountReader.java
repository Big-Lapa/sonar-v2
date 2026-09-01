package com.sonar.trading.reconciliation;

import com.sonar.trading.exchange.bybit.BybitFuturesClient;
import com.sonar.trading.exchange.bybit.BybitOrderMapper;
import com.sonar.trading.reconciliation.model.ExchangeBalance;
import com.sonar.trading.reconciliation.model.ExchangePosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@ConditionalOnProperty(name = "trading.exchange", havingValue = "LIVE")
public class BybitAccountReader implements AccountReader {

    private static final Logger log = LoggerFactory.getLogger(BybitAccountReader.class);

    private final BybitFuturesClient client;

    public BybitAccountReader(BybitFuturesClient client) {
        this.client = client;
    }

    @Override
    public String exchange() {
        return "BYBIT";
    }

    @Override
    public Optional<ExchangeBalance> getBalance() {
        return BybitAccountParser.parseBalance(client.getBalance(), "USDT");
    }

    @Override
    public List<ExchangePosition> getPositions() {
        return BybitAccountParser.parsePositions(client.getPositions(null));
    }

    @Override
    public BigDecimal sumIncomeSince(Instant since) {
        try {
            // null limit -> BybitFuturesHttpService clamps to each endpoint's own cap and paginates.
            Map<String, Object> resp = client.getIncome(null, since.toEpochMilli(), System.currentTimeMillis(), null);
            Object data = resp.get("data");
            if (!(data instanceof List<?> rows)) {
                return null;
            }
            BigDecimal sum = BigDecimal.ZERO;
            for (Object o : rows) {
                if (o instanceof Map<?, ?> row) {
                    @SuppressWarnings("unchecked")
                    BigDecimal income = BybitOrderMapper.extractDecimal((Map<String, Object>) row, "income");
                    if (income != null) {
                        sum = sum.add(income);
                    }
                }
            }
            return sum;
        } catch (RuntimeException e) {
            log.warn("[BYBIT] getIncome failed, keeping previous realized_pnl: {}", e.toString());
            return null;
        }
    }
}
