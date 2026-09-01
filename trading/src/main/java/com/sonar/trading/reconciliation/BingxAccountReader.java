package com.sonar.trading.reconciliation;

import com.sonar.trading.exchange.bingx.BingxFuturesClient;
import com.sonar.trading.exchange.bingx.BingxOrderMapper;
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
public class BingxAccountReader implements AccountReader {

    private static final Logger log = LoggerFactory.getLogger(BingxAccountReader.class);

    private final BingxFuturesClient client;

    public BingxAccountReader(BingxFuturesClient client) {
        this.client = client;
    }

    @Override
    public String exchange() {
        return "BINGX";
    }

    @Override
    public Optional<ExchangeBalance> getBalance() {
        // VST demo settles VST while config typically asks USDT — parseBalance falls back to
        // whatever row the venue returns (BUG-3/T22), so the requested asset here is advisory.
        return BingxAccountParser.parseBalance(client.getBalance(), "VST");
    }

    @Override
    public List<ExchangePosition> getPositions() {
        return BingxAccountParser.parsePositions(client.getPositions(null));
    }

    @Override
    @SuppressWarnings("unchecked")
    public BigDecimal sumIncomeSince(Instant since) {
        try {
            Map<String, Object> resp = client.getIncome(null, since.toEpochMilli(), System.currentTimeMillis(), 1000);
            Object data = resp.get("data");
            if (!(data instanceof List<?> rows)) {
                return null;
            }
            BigDecimal sum = BigDecimal.ZERO;
            for (Object o : rows) {
                if (o instanceof Map<?, ?> row) {
                    BigDecimal income = BingxOrderMapper.extractDecimal((Map<String, Object>) row, "income");
                    if (income != null) {
                        sum = sum.add(income);
                    }
                }
            }
            return sum;
        } catch (RuntimeException e) {
            log.warn("[BINGX] getIncome failed, keeping previous realized_pnl: {}", e.toString());
            return null;
        }
    }
}
