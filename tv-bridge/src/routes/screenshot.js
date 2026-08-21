const express = require('express');
const { screenshotPng } = require('../cdp');

const router = express.Router();

/** GET /screenshot — PNG of the current chart viewport (raw CDP Page.captureScreenshot). */
router.get('/screenshot', async (req, res) => {
  try {
    const png = await screenshotPng();
    res.set('Content-Type', 'image/png');
    res.send(png);
  } catch (e) {
    res.status(502).json({ error: e.message });
  }
});

module.exports = router;
