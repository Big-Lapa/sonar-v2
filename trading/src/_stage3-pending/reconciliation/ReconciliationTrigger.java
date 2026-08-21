package com.sonar.trading.reconciliation;

/**
 * Application event signalling that an order action (place/fill/cancel) just settled for a symbol,
 * so reconciliation should run for it (T17 post-order trigger). Published by the signal pipeline;
 * handled after the producing transaction commits. In PAPER there is no listener, so it is a no-op.
 */
public record ReconciliationTrigger(String symbol) {}
