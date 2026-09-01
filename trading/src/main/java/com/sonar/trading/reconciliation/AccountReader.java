package com.sonar.trading.reconciliation;

import com.sonar.trading.reconciliation.model.ExchangeBalance;
import com.sonar.trading.reconciliation.model.ExchangePosition;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Venue-agnostic account reads (balance, positions, realized income) — full multi-venue routing
 * for {@code MinutePoller} and the live-account controllers, resolved per exchange via
 * {@link AccountReaderRegistry} instead of a hardcoded BingX client. Mirrors the split between
 * {@code ExecutionAdapter} (order mutation) and this (read-only account state); both stay venue
 * -typed underneath ({@code BingxFuturesClient}/{@code BybitFuturesClient} + their parsers).
 */
public interface AccountReader {

    /** Uppercase venue name this reader serves — matches {@code account.exchange()} case-insensitively. */
    String exchange();

    Optional<ExchangeBalance> getBalance();

    /** All positions on the account (flat rows included) — callers filter by symbol themselves. */
    List<ExchangePosition> getPositions();

    /** Realized PnL + funding/fees since {@code since}. Null if the venue call failed (keep previous value). */
    BigDecimal sumIncomeSince(Instant since);
}
