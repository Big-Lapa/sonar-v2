const express = require('express');
const { evaluate } = require('../cdp');

const router = express.Router();

/**
 * GET /watchlist — ported from coin-analysis skill §0.5: the "Trade now" tab (class contains
 * `flag-lVZjovYi`) is clicked first, then symbols read via `[data-symbol-short]` + the
 * `uiMarker-` color-tag class. TradingView's internal class names are hashed/versioned and DO
 * drift across releases (the skill calls this out explicitly) — if the tab isn't found we still
 * read whatever list is currently showing rather than failing outright, and say so in the response.
 */
router.get('/watchlist', async (req, res) => {
  try {
    const script = `(() => {
      let tabSwitched = false;
      const tab = document.querySelector('[class*="flag-lVZjovYi"]');
      if (tab) { tab.click(); tabSwitched = true; }

      const syms = document.querySelectorAll('[data-symbol-short]');
      const out = [];
      syms.forEach((s) => {
        const symName = s.getAttribute('data-symbol-short');
        const marker = s.querySelector('[class*="uiMarker-"]');
        let color = 'none';
        if (marker) {
          const cls = marker.className.toString();
          const m = cls.match(/\\b(red|green|blue|yellow|orange|purple|pink|grey|gray|cyan|violet)-\\w+\\b/);
          color = m ? m[1] : cls;
        }
        out.push({ symbol: symName, color });
      });
      return { tabSwitched, symbols: out };
    })()`;
    const result = await evaluate(script);
    if (!result.tabSwitched) {
      result.warning = 'вкладка "Trade now" не найдена (класс мог смениться) — прочитан текущий список как есть';
    }
    res.json(result);
  } catch (e) {
    res.status(502).json({ error: e.message });
  }
});

/**
 * POST /watchlist/add  { symbol }
 * Best-effort — TradingView's add-symbol flow isn't documented in the skill (only reading/tab
 * switching is). Tries the common `data-name="add-symbol-button"` control; reports a clear error
 * if the selector doesn't match this TradingView build instead of failing silently.
 */
router.post('/watchlist/add', async (req, res) => {
  const { symbol } = req.body || {};
  if (!symbol) return res.status(400).json({ error: 'symbol обязателен' });
  try {
    const script = `(async () => {
      const addBtn = document.querySelector('[data-name="add-symbol-button"], [class*="addButton"]');
      if (!addBtn) return { added: false, reason: 'кнопка добавления не найдена в DOM' };
      addBtn.click();
      await new Promise((r) => setTimeout(r, 300));
      const input = document.querySelector('input[data-role="search"], input[class*="search"]');
      if (!input) return { added: false, reason: 'поле поиска не найдено после клика по +' };
      const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
      setter.call(input, ${JSON.stringify(symbol)});
      input.dispatchEvent(new Event('input', { bubbles: true }));
      await new Promise((r) => setTimeout(r, 500));
      const firstResult = document.querySelector('[data-role="list-item"], [class*="symbolTitle"]');
      if (!firstResult) return { added: false, reason: 'нет результатов поиска' };
      firstResult.click();
      return { added: true };
    })()`;
    const result = await evaluate(script);
    res.json(result);
  } catch (e) {
    res.status(502).json({ error: e.message });
  }
});

module.exports = router;
