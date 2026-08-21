-- Positions: fields needed for portfolio risk limit and stop-watchdog.
ALTER TABLE trading.positions
  ADD COLUMN sl_price            VARCHAR(30),
  ADD COLUMN tp_price            VARCHAR(30),
  ADD COLUMN planned_risk_amount VARCHAR(30);

CREATE TABLE trading.account_risk (
  account_id                  VARCHAR(100) PRIMARY KEY,
  risk_pct_per_trade          VARCHAR(30) NOT NULL DEFAULT '0.005',
  max_portfolio_risk_pct      VARCHAR(30) NOT NULL DEFAULT '0.02',
  max_position_notional       VARCHAR(30) NOT NULL DEFAULT '2000',
  max_daily_realized_loss_pct VARCHAR(30) NOT NULL DEFAULT '0.03',
  max_equity_drawdown_pct     VARCHAR(30) NOT NULL DEFAULT '0.15',
  max_open_positions          INTEGER     NOT NULL DEFAULT 5,
  max_leverage                INTEGER     NOT NULL DEFAULT 5,
  tradable                    BOOLEAN     NOT NULL DEFAULT false,
  version                     INTEGER     NOT NULL DEFAULT 1,
  updated_at                  BIGINT      NOT NULL
);

-- Daily accounting baseline: MUST be in DB, otherwise a restart resets the daily limit.
CREATE TABLE trading.daily_baseline (
  account_id      VARCHAR(100) NOT NULL,
  trading_day     DATE         NOT NULL,   -- UTC 00:00 boundary
  equity_at_open  VARCHAR(30)  NOT NULL,
  realized_pnl    VARCHAR(30)  NOT NULL DEFAULT '0',
  peak_equity     VARCHAR(30)  NOT NULL,
  PRIMARY KEY (account_id, trading_day)
);

CREATE TABLE trading.strategy_settings (  -- single row, id=1
  id                       BIGINT PRIMARY KEY,
  min_sl_distance_pct      VARCHAR(30) NOT NULL DEFAULT '0.015',
  min_sl_cost_mult         VARCHAR(30) NOT NULL DEFAULT '3',
  max_sl_distance_pct      VARCHAR(30) NOT NULL DEFAULT '0.12',
  min_rr                   VARCHAR(30) NOT NULL DEFAULT '1.8',
  min_24h_volume_usd       VARCHAR(30) NOT NULL DEFAULT '20000000',
  max_move_24h_pct         VARCHAR(30) NOT NULL DEFAULT '0.05',
  max_move_7d_pct          VARCHAR(30) NOT NULL DEFAULT '0.15',
  max_candidates           INTEGER     NOT NULL DEFAULT 3,
  llm_model                VARCHAR(60) NOT NULL DEFAULT 'claude-sonnet-5',
  always_draw_tv           BOOLEAN     NOT NULL DEFAULT false,
  proposal_ttl_ms          BIGINT      NOT NULL DEFAULT 600000,
  hold_reminder_days       INTEGER     NOT NULL DEFAULT 7,
  search_cron_hours        VARCHAR(60) NOT NULL DEFAULT '*',  -- UTC hours the cron checks slots
  version                  INTEGER     NOT NULL DEFAULT 1,
  updated_at               BIGINT      NOT NULL
);

CREATE TABLE trading.symbol_blacklist (
  symbol     VARCHAR(20) PRIMARY KEY,
  reason     VARCHAR(300),
  created_at BIGINT NOT NULL
);

-- Strategy state machine: the strategy service is stateless, everything lives here.
CREATE TABLE trading.strategy_state (
  id                 BIGINT PRIMARY KEY,          -- single row
  phase              VARCHAR(30) NOT NULL,        -- IDLE|PROMPT_PENDING|SEARCHING|PROPOSALS_PENDING
  prompt_message_id  BIGINT,                      -- telegram message_id of the live prompt
  prompt_sent_at     BIGINT,
  updated_at         BIGINT NOT NULL
);

CREATE TABLE trading.proposals (
  proposal_id   VARCHAR(40) PRIMARY KEY,
  symbol        VARCHAR(20) NOT NULL,
  side          VARCHAR(10) NOT NULL,             -- LONG (v1 long-only)
  entry_price   VARCHAR(30) NOT NULL,
  sl_price      VARCHAR(30) NOT NULL,
  tp_price      VARCHAR(30) NOT NULL,
  rationale     TEXT,
  status        VARCHAR(20) NOT NULL,             -- PENDING|CONFIRMED|REJECTED|EXPIRED|STALE
  created_at    BIGINT NOT NULL,
  decided_at    BIGINT,
  tg_message_id BIGINT
);
