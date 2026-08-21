package com.sonar.trading.config;

/** One entry of the account registry (11: {@code TRADING_ACCOUNTS_N_*} in `.env`). */
public record AccountProps(String id, String exchange) {
}
