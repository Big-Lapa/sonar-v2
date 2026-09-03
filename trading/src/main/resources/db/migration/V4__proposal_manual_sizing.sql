-- Manual trading panel: notional-based sizing (qty = notional / entry) instead of risk%, plus
-- leverage to actually set on the exchange before entry. Both nullable — a proposal created
-- without them keeps the risk%-based sizing path.
ALTER TABLE trading.proposals ADD COLUMN notional VARCHAR(30);
ALTER TABLE trading.proposals ADD COLUMN leverage INTEGER;
