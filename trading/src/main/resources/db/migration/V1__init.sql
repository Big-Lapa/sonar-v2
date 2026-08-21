CREATE SCHEMA IF NOT EXISTS trading;

-- Signal dedup, second idempotency layer under proposals.status (4.1): confirming a proposal
-- is keyed by (idempotency_key=proposal_id, account_id) so a retried confirm for one account
-- in the fan-out doesn't double-fire while other accounts are still pending.
CREATE TABLE trading.signal_inbox (
    id              BIGSERIAL PRIMARY KEY,
    idempotency_key TEXT   NOT NULL,
    account_id      TEXT   NOT NULL,
    signal_id       TEXT   NOT NULL,
    status          TEXT   NOT NULL DEFAULT 'PROCESSING',
    received_at     BIGINT NOT NULL,
    processed_at    BIGINT,
    CONSTRAINT uq_signal_inbox_key UNIQUE (idempotency_key, account_id)
);

-- Order commands produced from a confirmed proposal (execution intent, audit trail).
CREATE TABLE trading.order_commands (
    id              BIGSERIAL PRIMARY KEY,
    command_id      TEXT NOT NULL,
    account_id      TEXT NOT NULL,
    proposal_id     TEXT NOT NULL,
    client_order_id TEXT NOT NULL,
    exchange        TEXT NOT NULL,
    symbol          TEXT NOT NULL,
    side            TEXT NOT NULL,
    action          TEXT NOT NULL,
    quantity        NUMERIC(30, 10) NOT NULL,
    order_type      TEXT NOT NULL DEFAULT 'MARKET',
    price           NUMERIC(30, 10),
    status          TEXT NOT NULL DEFAULT 'PENDING',
    created_at      BIGINT NOT NULL,
    CONSTRAINT uq_order_commands_command_id      UNIQUE (command_id),
    CONSTRAINT uq_order_commands_client_order_id UNIQUE (client_order_id)
);
CREATE INDEX idx_order_commands_account ON trading.order_commands (account_id);

-- Orders: execution lifecycle
CREATE TABLE trading.orders (
    id                  BIGSERIAL PRIMARY KEY,
    account_id          TEXT NOT NULL,
    client_order_id     TEXT NOT NULL,
    command_id          TEXT NOT NULL,
    exchange_order_id   TEXT,
    exchange            TEXT NOT NULL,
    symbol              TEXT NOT NULL,
    side                TEXT NOT NULL,
    action              TEXT NOT NULL,
    quantity            NUMERIC(30, 10) NOT NULL,
    status              TEXT NOT NULL,
    filled_quantity     NUMERIC(30, 10),
    avg_fill_price      NUMERIC(30, 10),
    fee                 NUMERIC(30, 10),
    error_code          TEXT,
    error_message       TEXT,
    exchange_event_time BIGINT,
    created_at          BIGINT NOT NULL,
    updated_at          BIGINT NOT NULL,
    CONSTRAINT uq_orders_client_order_id UNIQUE (client_order_id)
);
CREATE INDEX idx_orders_account ON trading.orders (account_id);

-- Individual fills
CREATE TABLE trading.fills (
    id         BIGSERIAL PRIMARY KEY,
    account_id TEXT NOT NULL,
    order_id   BIGINT NOT NULL REFERENCES trading.orders (id),
    symbol     TEXT NOT NULL,
    quantity   NUMERIC(30, 10) NOT NULL,
    price      NUMERIC(30, 10) NOT NULL,
    fee        NUMERIC(30, 10),
    filled_at  BIGINT NOT NULL
);
CREATE INDEX idx_fills_account ON trading.fills (account_id);

-- Positions: one active record per (account_id, symbol) — One-Way Mode.
-- sl_price/tp_price/planned_risk_amount: needed for the portfolio risk limit and stop-watchdog (4.1/4.2).
CREATE TABLE trading.positions (
    id                  BIGSERIAL PRIMARY KEY,
    position_id         TEXT NOT NULL,
    account_id          TEXT NOT NULL,
    exchange            TEXT NOT NULL,
    symbol              TEXT NOT NULL,
    side                TEXT NOT NULL DEFAULT 'FLAT',
    quantity            NUMERIC(30, 10) NOT NULL DEFAULT 0,
    entry_price         NUMERIC(30, 10),
    mark_price          NUMERIC(30, 10),
    unrealized_pnl      NUMERIC(30, 10),
    realized_pnl        NUMERIC(30, 10) NOT NULL DEFAULT 0,
    liquidation_price   NUMERIC(30, 10),
    leverage             INT,
    status               TEXT NOT NULL DEFAULT 'CLOSED',
    opened_at            BIGINT,
    updated_at           BIGINT NOT NULL,
    version              BIGINT NOT NULL DEFAULT 0,
    sl_price             VARCHAR(30),
    tp_price             VARCHAR(30),
    planned_risk_amount  VARCHAR(30),
    CONSTRAINT uq_positions_position_id      UNIQUE (position_id),
    CONSTRAINT uq_positions_account_symbol   UNIQUE (account_id, symbol)
);

-- Cooldown tracker per (account, symbol)
CREATE TABLE trading.trade_cooldowns (
    id           BIGSERIAL PRIMARY KEY,
    account_id   TEXT NOT NULL,
    symbol       TEXT NOT NULL,
    last_loss_at BIGINT NOT NULL,
    CONSTRAINT uq_cooldown_account_symbol UNIQUE (account_id, symbol)
);

-- Persistent trading state (global auto-pause flag)
CREATE TABLE trading.trading_state (
    key        TEXT PRIMARY KEY,
    value      TEXT NOT NULL,
    updated_at BIGINT NOT NULL
);
INSERT INTO trading.trading_state (key, value, updated_at) VALUES ('auto_pause', 'false', 0);

-- Outbox (crash-safety for cross-module handlers, not Kafka — see root CLAUDE.md conventions)
CREATE TABLE trading.outbox (
    id            BIGSERIAL PRIMARY KEY,
    topic         TEXT    NOT NULL,
    partition_key TEXT    NOT NULL,
    payload       TEXT    NOT NULL,
    created_at    BIGINT  NOT NULL,
    published_at  BIGINT
);
CREATE INDEX ix_trading_outbox_unpublished ON trading.outbox (id) WHERE published_at IS NULL;

-- Per-account risk profile (4.2). No per-symbol profiles.
CREATE TABLE trading.account_risk (
    account_id                   VARCHAR(100) PRIMARY KEY,
    risk_pct_per_trade           VARCHAR(30) NOT NULL DEFAULT '0.005',
    max_portfolio_risk_pct       VARCHAR(30) NOT NULL DEFAULT '0.02',
    max_position_notional        VARCHAR(30) NOT NULL DEFAULT '2000',
    max_daily_realized_loss_pct  VARCHAR(30) NOT NULL DEFAULT '0.03',
    max_equity_drawdown_pct      VARCHAR(30) NOT NULL DEFAULT '0.15',
    max_open_positions           INTEGER     NOT NULL DEFAULT 5,
    max_leverage                 INTEGER     NOT NULL DEFAULT 5,
    tradable                     BOOLEAN     NOT NULL DEFAULT false,
    version                      INTEGER     NOT NULL DEFAULT 1,
    updated_at                   BIGINT      NOT NULL
);

-- Daily accounting baseline: MUST be in DB, otherwise a restart resets the daily limit.
CREATE TABLE trading.daily_baseline (
    account_id     VARCHAR(100) NOT NULL,
    trading_day    DATE         NOT NULL,   -- UTC 00:00 boundary
    equity_at_open VARCHAR(30)  NOT NULL,
    realized_pnl   VARCHAR(30)  NOT NULL DEFAULT '0',
    peak_equity    VARCHAR(30)  NOT NULL,
    PRIMARY KEY (account_id, trading_day)
);

CREATE TABLE trading.strategy_settings (  -- single row, id=1
    id                  BIGINT      PRIMARY KEY,
    min_sl_distance_pct VARCHAR(30) NOT NULL DEFAULT '0.015',
    min_sl_cost_mult    VARCHAR(30) NOT NULL DEFAULT '3',
    max_sl_distance_pct VARCHAR(30) NOT NULL DEFAULT '0.12',
    min_rr              VARCHAR(30) NOT NULL DEFAULT '1.8',
    min_24h_volume_usd  VARCHAR(30) NOT NULL DEFAULT '20000000',
    max_move_24h_pct    VARCHAR(30) NOT NULL DEFAULT '0.05',
    max_move_7d_pct     VARCHAR(30) NOT NULL DEFAULT '0.15',
    max_candidates      INTEGER     NOT NULL DEFAULT 3,
    llm_model           VARCHAR(60) NOT NULL DEFAULT 'claude-sonnet-5',
    always_draw_tv      BOOLEAN     NOT NULL DEFAULT false,
    proposal_ttl_ms     BIGINT      NOT NULL DEFAULT 600000,
    hold_reminder_days  INTEGER     NOT NULL DEFAULT 7,
    search_cron_hours   VARCHAR(60) NOT NULL DEFAULT '*',  -- UTC hours the cron checks slots
    version             INTEGER     NOT NULL DEFAULT 1,
    updated_at          BIGINT      NOT NULL
);

CREATE TABLE trading.symbol_blacklist (
    symbol     VARCHAR(20) PRIMARY KEY,
    reason     VARCHAR(300),
    created_at BIGINT NOT NULL
);

-- Strategy state machine: the strategy service is stateless, everything lives here.
CREATE TABLE trading.strategy_state (
    id                BIGINT      PRIMARY KEY,  -- single row
    phase             VARCHAR(30) NOT NULL,     -- IDLE|PROMPT_PENDING|SEARCHING|PROPOSALS_PENDING
    prompt_message_id BIGINT,                   -- telegram message_id of the live prompt
    prompt_sent_at    BIGINT,
    updated_at        BIGINT NOT NULL
);

CREATE TABLE trading.proposals (
    proposal_id   VARCHAR(40) PRIMARY KEY,
    symbol        VARCHAR(20) NOT NULL,
    side          VARCHAR(10) NOT NULL,  -- LONG (v1 long-only)
    entry_price   VARCHAR(30) NOT NULL,
    sl_price      VARCHAR(30) NOT NULL,
    tp_price      VARCHAR(30) NOT NULL,
    rationale     TEXT,
    status        VARCHAR(20) NOT NULL,  -- PENDING|CONFIRMED|REJECTED|EXPIRED|STALE
    created_at    BIGINT NOT NULL,
    decided_at    BIGINT,
    tg_message_id BIGINT
);

-- Exchange-vs-DB discrepancies: no reconciliation.events producer anymore, events land here.
CREATE TABLE trading.reconciliation_events (
    id         BIGSERIAL   PRIMARY KEY,
    account_id VARCHAR(100) NOT NULL,
    symbol     VARCHAR(20),
    kind       VARCHAR(40) NOT NULL,  -- QTY_MISMATCH | ORPHAN_EXCHANGE | ORPHAN_LOCAL | MISSING_STOP
    details    TEXT,
    created_at BIGINT NOT NULL
);
