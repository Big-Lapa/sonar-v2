const express = require('express');
const { evaluate } = require('../cdp');

const router = express.Router();

/** Every shape this bridge draws is tagged so a redraw only touches its own lines — the user's
 *  own manual drawings (untagged) are never inventoried or removed (skill 4.0: draw_clear
 *  forbidden precisely because it takes those with it). */
const TAG = '[sonar]';

const COLOR_BY_KIND = {
  support: '#26a69a',
  resistance: '#ef5350',
  hvn: '#ab47bc',
};

/**
 * POST /draw  { symbol, levels: [{price, label, kind: support|resistance|hvn}] }
 * The bridge does NOT compute levels itself (4.3: "мостик не считает свои — иначе картинка
 * разойдётся с числами") — it only draws what `strategy` already computed.
 */
router.post('/draw', async (req, res) => {
  const { symbol, levels } = req.body || {};
  if (!symbol || !Array.isArray(levels)) {
    return res.status(400).json({ error: 'symbol и levels[] обязательны' });
  }

  try {
    const script = `(async () => {
      const chart = window.TradingViewApi._activeChartWidgetWV.value();
      const currentSymbol = chart.symbol();
      const wantSymbol = ${JSON.stringify(symbol)};
      if (!currentSymbol.toUpperCase().includes(wantSymbol.toUpperCase())) {
        await chart.setSymbol(wantSymbol);
      }

      // Inventory: remove only our own previously-drawn shapes (tag-prefixed text).
      const existing = chart.getAllShapes();
      for (const s of existing) {
        try {
          const shape = chart.getShapeById(s.id);
          const props = shape.getProperties ? shape.getProperties() : {};
          if (props && typeof props.text === 'string' && props.text.startsWith(${JSON.stringify(TAG)})) {
            chart.removeEntity(s.id);
          }
        } catch (e) { /* shape API surface varies by type — skip anything we can't introspect */ }
      }

      const range = chart.getVisibleRange();
      const anchorTime = range.to - Math.round((range.to - range.from) * 0.05);
      const levels = ${JSON.stringify(levels)};
      const colorByKind = ${JSON.stringify(COLOR_BY_KIND)};
      const drawn = [];
      for (const lvl of levels) {
        const id = await chart.createShape(
          { time: anchorTime, price: lvl.price },
          {
            shape: 'horizontal_line',
            text: ${JSON.stringify(TAG)} + ' ' + lvl.label + ' ' + lvl.price,
            overrides: { linecolor: colorByKind[lvl.kind] || '#5b9dff', linewidth: 1, linestyle: lvl.kind === 'hvn' ? 2 : 0 },
          },
        );
        drawn.push({ id: String(id), label: lvl.label, price: lvl.price, kind: lvl.kind });
      }
      return { symbol: chart.symbol(), drawn };
    })()`;

    const result = await evaluate(script);
    res.json(result);
  } catch (e) {
    res.status(502).json({ error: e.message });
  }
});

module.exports = router;
