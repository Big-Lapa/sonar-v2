package com.sonar.trading.reconciliation;

import com.sonar.trading.exchange.binance.BinanceFuturesClient;
import com.sonar.trading.exchange.binance.BinanceOrderMapper;
import com.sonar.trading.reconciliation.model.ExchangeBalance;
import com.sonar.trading.reconciliation.model.ExchangePosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class BinanceAccountReader implements AccountReader {

    private static final Logger log = LoggerFactory.getLogger(BinanceAccountReader.class);

    private final BinanceFuturesClient client;

    public BinanceAccountReader(BinanceFuturesClient client) {
        this.client = client;
    }

    @Override
    public String exchange() {
        return "BINANCE";
    }

    @Override
    public Optional<ExchangeBalance> getBalance() {
        return BinanceAccountParser.parseBalance(client.getBalance(), "USDT");
    }

    @Override
    public List<ExchangePosition> getPositions() {
        return BinanceAccountParser.parsePositions(client.getPositions(null));
    }

    @Override
    public BigDecimal sumIncomeSince(Instant since) {
        try {
            Map<String, Object> resp = client.getIncome(null, since.toEpochMilli(), System.currentTimeMillis(), 1000);
            BigDecimal sum = BigDecimal.ZERO;
            for (Map<String, Object> row : BinanceOrderMapper.dataRows(resp)) {
                BigDecimal income = BinanceOrderMapper.extractDecimal(row, "income");
                if (income != null) {
                    sum = sum.add(income);
                }
            }
            return sum;
        } catch (RuntimeException e) {
            log.warn("[BINANCE] getIncome failed, keeping previous realized_pnl: {}", e.toString());
            return null;
        }
    }
}
