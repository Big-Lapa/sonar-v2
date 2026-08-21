CREATE SCHEMA IF NOT EXISTS trading;

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
CREATE TABLE trading.positions (
    id                 BIGSERIAL PRIMARY KEY,
    position_id        TEXT NOT NULL,
    account_id         TEXT NOT NULL,
    exchange           TEXT NOT NULL,
    symbol             TEXT NOT NULL,
    side               TEXT NOT NULL DEFAULT 'FLAT',
    quantity           NUMERIC(30, 10) NOT NULL DEFAULT 0,
    entry_price        NUMERIC(30, 10),
    mark_price         NUMERIC(30, 10),
    unrealized_pnl     NUMERIC(30, 10),
    realized_pnl       NUMERIC(30, 10) NOT NULL DEFAULT 0,
    liquidation_price  NUMERIC(30, 10),
    leverage           INT,
    status             TEXT NOT NULL DEFAULT 'CLOSED',
    opened_at          BIGINT,
    updated_at         BIGINT NOT NULL,
    version            BIGINT NOT NULL DEFAULT 0,
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

-- Outbox (crash-safety for cross-module handlers, not Kafka — see ADR reference in root CLAUDE.md)
CREATE TABLE trading.outbox (
    id            BIGSERIAL PRIMARY KEY,
    topic         TEXT    NOT NULL,
    partition_key TEXT    NOT NULL,
    payload       TEXT    NOT NULL,
    created_at    BIGINT  NOT NULL,
    published_at  BIGINT
);
CREATE INDEX ix_trading_outbox_unpublished ON trading.outbox (id) WHERE published_at IS NULL;
