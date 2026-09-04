-- Manual multi-exchange management: a proposal can now target a single account instead of
-- always fanning out across every tradable one. NULL is legacy/untargeted (kept nullable so
-- existing rows and any external caller that omits it don't break).
ALTER TABLE trading.proposals ADD COLUMN account_id VARCHAR(100);
