-- Stage 4: minute poller needs somewhere to flag a DB/exchange qty mismatch (admin highlight, 4.7)
-- and somewhere to queue operator-facing events for strategy to relay to Telegram (4.8: strategy has
-- no incoming REST, so trading queues; strategy pulls with the same minute tick it already needs).
ALTER TABLE trading.positions
  ADD COLUMN qty_mismatch BOOLEAN NOT NULL DEFAULT false;

-- trading.strategy_settings already exists (V1) — the poller only reads hold_reminder_days from it.

CREATE TABLE trading.notifications (
  id         BIGSERIAL   PRIMARY KEY,
  kind       VARCHAR(40) NOT NULL, -- POSITION_CLOSED | MISSING_STOP | QTY_MISMATCH | HOLD_REMINDER
  account_id VARCHAR(100),
  symbol     VARCHAR(20),
  message    TEXT        NOT NULL,
  created_at BIGINT      NOT NULL
);
CREATE INDEX idx_notifications_created_at ON trading.notifications (id);
