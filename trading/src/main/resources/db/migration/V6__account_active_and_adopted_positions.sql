-- Splits "poll this account's balance/positions" from "tradable" (Sonar may open here). Before
-- this, MinutePoller skipped a whole account's monitoring — balance, position reconciliation,
-- stop verification, close notifications — whenever tradable=false, which made "just watch this
-- account, don't let Sonar trade it" impossible (exactly the state a freshly-added read-only
-- venue like Pionex starts in). active gates monitoring; tradable keeps gating execution only.
ALTER TABLE trading.account_risk ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;

-- Tags a position row's origin — informational only, doesn't affect risk gating, stop
-- verification, or notifications. EXCHANGE rows are positions MinutePoller found open on the
-- venue with no matching DB row (opened directly on the exchange, or via another tool) and
-- adopted rather than requiring Sonar to have placed the order itself — the exchange is the
-- source of truth for "is this position open", not our own execution history.
ALTER TABLE trading.positions ADD COLUMN opened_via VARCHAR(10) NOT NULL DEFAULT 'SONAR';
