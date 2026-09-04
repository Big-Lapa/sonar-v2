-- Manual trading panel's entry ticket lets the operator pick LIMIT, but the proposal only ever
-- carried entryPrice — ProposalExecutionService hardcoded MARKET regardless, so a LIMIT selection
-- silently executed as an immediate market order. order_type persists the actual choice;
-- limit_price is the price to place the resting order at (null for MARKET).
ALTER TABLE trading.proposals ADD COLUMN order_type VARCHAR(10) NOT NULL DEFAULT 'MARKET';
ALTER TABLE trading.proposals ADD COLUMN limit_price VARCHAR(30);
