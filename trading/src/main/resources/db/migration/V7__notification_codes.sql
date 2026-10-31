-- Operator-facing notifications carry a stable message code + JSON params so the admin panel and
-- the Telegram bot can render them in the viewer's language. `message` stays as the English fallback;
-- rows written before this migration have no code and are shown as stored.
ALTER TABLE trading.notifications
  ADD COLUMN code   VARCHAR(60),
  ADD COLUMN params TEXT;
