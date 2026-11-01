package com.sonar.trading.reconciliation;

import com.sonar.trading.exchange.pionex.PionexFuturesClient;
import com.sonar.trading.reconciliation.model.ExchangeBalance;
import com.sonar.trading.reconciliation.model.ExchangePosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Venue-agnostic account reads for Pionex — mirrors {@link BingxAccountReader}. Read-only
 * iteration: no {@code ExecutionAdapter} exists for Pionex yet, so this account can be read
 * (balances/positions show up in the panel) but stays {@code tradable=false} — nothing in the
 * execution path can pick it for entries until that adapter is written.
 */
public class PionexAccountReader implements AccountReader {

    private static final Logger log = LoggerFactory.getLogger(PionexAccountReader.class);

    private final PionexFuturesClient client;
    private final Set<String> warnedUnpriced = ConcurrentHashMap.newKeySet();

    public PionexAccountReader(PionexFuturesClient client) {
        this.client = client;
    }

    @Override
    public String exchange() {
        return "PIONEX";
    }

    /**
     * Pionex's balances call is a coin wallet ({@code free}/{@code frozen}/{@code debts}), not a
     * margin-account summary — confirmed live (2026-09-07): no equity or PnL field at that level
     * at all, unlike BingX/Bybit. So {@code equity} here is folded in from open positions'
     * {@code unrealizedPnL} (same {@code balance + Σ uPnL} shape {@code MinutePoller}'s own
     * fallback already uses when a venue balance has no venue-reported equity) — costing one extra
     * signed call per balance read, which the panel's throttling / poller's minute cadence both
     * already amortize fine.
     *
     * <p>{@code balance} is the whole wallet valued in USDT ({@link PionexAccountParser#valueWallet}):
     * the account's money can sit in BTC rather than USDT, and counting the USDT row alone reported
     * equity 0 for a ~$230 account. {@code available} stays the free USDT only. A failed price
     * lookup throws — the poller then skips the tick instead of recording a falsely low equity.
     */
    @Override
    public Optional<ExchangeBalance> getBalance() {
        Optional<PionexAccountParser.WalletValue> wallet =
                PionexAccountParser.valueWallet(client.getBalances(), this::priceInUsdt);
        if (wallet.isEmpty()) {
            return Optional.empty();
        }
        PionexAccountParser.WalletValue w = wallet.get();
        warnUnpricedOnce(w.unpricedCoins());
        BigDecimal pnl = sumUnrealizedPnl();
        BigDecimal equity = pnl != null ? w.total().add(pnl) : w.total();
        return Optional.of(new ExchangeBalance("USDT", w.total(), equity, pnl, w.freeUsdt(), null));
    }

    private PionexAccountParser.CoinPrice priceInUsdt(String coin) {
        return PionexAccountParser.parseBookTicker(client.getBookTicker(coin + "_USDT"));
    }

    /** A coin with no USDT pair stays unpriced forever — say so once per run, not every minute. */
    private void warnUnpricedOnce(List<String> coins) {
        for (String coin : coins) {
            if (warnedUnpriced.add(coin)) {
                log.warn("[PIONEX] {} has no {}_USDT pair — left out of the account balance", coin, coin);
            }
        }
    }

    /** Null (not zero) if the positions call itself failed — the caller then falls back to plain
     *  wallet balance rather than showing a confidently-wrong equity. */
    private BigDecimal sumUnrealizedPnl() {
        try {
            List<ExchangePosition> positions = getPositions();
            BigDecimal sum = BigDecimal.ZERO;
            boolean any = false;
            for (ExchangePosition p : positions) {
                if (p.unrealizedPnl() != null) {
                    sum = sum.add(p.unrealizedPnl());
                    any = true;
                }
            }
            return any ? sum : BigDecimal.ZERO;
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public List<ExchangePosition> getPositions() {
        return PionexAccountParser.parsePositions(client.getPositions(null));
    }

    /**
     * No income/PnL-history endpoint exists in the v1 reference client — realized PnL for this
     * account is unknown, not zero. {@code null} is the established convention for "couldn't
     * determine this" ({@code BingxAccountReader}/{@code BybitAccountReader} return the same on a
     * failed call) — every caller ({@code MinutePoller.updateDailyBaseline}, {@code
     * PollerTransactions.upsertDailyBaseline}) already treats it as "keep the previous value",
     * never as a hard failure. Harmless while {@code tradable=false}; revisit once a real
     * fills/income endpoint is found for the execution iteration.
     */
    @Override
    public BigDecimal sumIncomeSince(Instant since) {
        return null;
    }
}
