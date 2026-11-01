package com.sonar.trading.i18n;

/**
 * Stable codes for operator-facing messages (op results, leg errors, sizing rejections,
 * notifications). The API carries {@code code + params}; the admin panel and the Telegram bot
 * translate them (dictionary key {@code msg.<CODE>}). The English {@code template} here is the
 * fallback stored in the DB and returned as plain text.
 *
 * <p>Template syntax: {@code {name}} is a parameter; {@code [ ... {name} ... ]} is an optional
 * group, dropped when any parameter inside it is missing. The same syntax is used by the
 * client-side dictionaries.
 */
public enum MessageCode {
    NO_OPEN_POSITIONS("no open positions[ for {symbol}]"),
    VENUE_NOT_CONNECTED("venue of account '{account}' is not connected"),
    VENUE_UNSUPPORTED("venue '{venue}' is not connected in this version"),
    CLOSE_ALL_SENT("close-all sent for {count} position(s), confirmation will come from the poller"),
    CLOSE_ALL_FAILED("close-all failed: {error}"),
    POSITION_NOT_FOUND("position not found or not open"),
    QTY_MUST_BE_POSITIVE("qty must be positive"),
    PRICE_REQUIRED_FOR_LIMIT("price is required for a limit close"),
    POSITION_ALREADY_CLOSED("position is already closed on the exchange"),
    QTY_BELOW_LOT_STEP("qty {qty} is below the exchange lot step for {symbol}"),
    CLOSE_LIMIT_SENT("limit close order for {qty} {symbol} sent (state={state}), confirmation will come from the poller"),
    CLOSE_MARKET_SENT("market close of {qty} {symbol} sent (state={state}), confirmation will come from the poller"),
    CLOSE_FAILED("close failed: {error}"),
    STOP_TYPE_INVALID("type must be sl or tp"),
    STOP_MOVED_BREAKEVEN("stop moved to breakeven {price}"),
    SL_MOVED("SL moved to {price}"),
    TP_MOVED("TP moved to {price}"),
    STOP_MOVE_FAILED("stop move failed: {error}"),
    PARTIAL_CLOSE_STOPS_NOT_RESCALED("SL/TP were not recalculated after the partial close, check the position's protection: {error}"),
    STOP_MOVE_UNPROTECTED("stop move failed, check the position's protection: {error}"),

    NO_EXCHANGE_FOR_PRICE_CHECK("no connected exchange to check the price — execution cancelled"),
    PRICE_UNAVAILABLE("could not get the current price of {symbol} — execution cancelled"),
    PRICE_DRIFT("price moved away from the proposal: entry={entry} now={now} drift={drift}% (limit 0.5%)"),
    EXCHANGE_UNAVAILABLE_FOR_PRICE("exchange unavailable for the price check — execution cancelled: {error}"),
    ACCOUNT_NOT_FOUND("account not found in the registry"),
    ALREADY_PROCESSED("already processed (idempotency)"),
    ORDER_PLACE_FAILED("order placement failed: {error}"),
    ORDER_NOT_FILLED("order did not confirm as FILLED after 5 attempts (state={state})"),
    NOTIONAL_CAP_EXCEEDED("would exceed the account notional cap: open ${open} + reserved (pending limit) ${pending} + new ${added} = ${total} > limit ${limit}"),
    LEVERAGE_CAP_EXCEEDED("would exceed the account leverage cap: resulting notional ${notional} > equity x max leverage ${limit}"),
    PREVIOUSLY_FINISHED("previously finished with status {status}"),
    ORDER_NOT_FOUND("order not found"),
    ORDER_CANCELLED("order {orderId} cancelled"),
    ORDER_CANCEL_FAILED("order cancellation failed"),
    ACCOUNT_NOT_TRADABLE("account is excluded from trading (tradable=false)"),
    NO_EQUITY_DATA("no equity data for today — wait for the minute poller (stage 4)"),
    LIMIT_ORDER_WORKING("limit order placed on the exchange, waiting to fill"),

    EQUITY_UNAVAILABLE("equity is unavailable or not positive"),
    SL_EQUALS_ENTRY("stop loss equals the entry price"),
    NOTIONAL_MUST_BE_POSITIVE("notional must be positive"),
    BELOW_EXCHANGE_MINIMUM("failed exchange minimums: {error}"),
    EXCEEDS_ACCOUNT_LIMITS("exchange minimum lot ({qty}, notional ${notional}) exceeds account limit ${limit}"),
    NOTIONAL_EXCEEDS_POSITION_LIMIT("notional ${notional} exceeds the account position limit ${limit}"),
    NOTIONAL_EXCEEDS_LEVERAGE_LIMIT("notional ${notional} exceeds the account leverage limit (equity x maxLeverage ${limit})"),

    ANOMALY_EQUITY("abnormal equity drop {previous} -> {equity} — looks like a demo balance reset, operator confirmation required"),
    POSITION_ADOPTED("{side} {qty} @ {price} — opened directly on the exchange, started tracking it (SL/TP unknown)"),
    QTY_MISMATCH("quantity mismatch: DB={db} exchange={exchange}"),
    MISSING_STOP("position has no stop, auto-recovery failed — manual intervention required"),
    LIMIT_FILLED_NO_STOP("limit order filled ({qty} @ {price}), but no stop found on the exchange — check the position's protection"),
    POSITION_CLOSED("{symbol} closed, [PnL={pnl}, ]held {hours}h{minutes}m"),
    POSITION_CLOSED_DETECTED("{symbol} closed, [PnL={pnl} (since detection, not since open), ]held {hours}h{minutes}m"),
    POSITION_CLOSED_PNL_PENDING("{symbol} closed, PnL pending, held {hours}h{minutes}m"),
    POSITION_CLOSED_DETECTED_PNL_PENDING("{symbol} closed, PnL pending (since detection, not since open), held {hours}h{minutes}m"),
    HOLD_REMINDER("{symbol} held for {days} d. (threshold {threshold})");

    private final String template;

    MessageCode(String template) {
        this.template = template;
    }

    public String template() {
        return template;
    }
}
