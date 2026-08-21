const express = require('express');
const { healthCheck } = require('./cdp');
const drawRoute = require('./routes/draw');
const watchlistRoute = require('./routes/watchlist');
const screenshotRoute = require('./routes/screenshot');

/**
 * tv-bridge (2, 7): Windows-host process, CDP to TradingView Desktop. No ADMIN_TOKEN —
 * listens only on 127.0.0.1 (2: "strategy → tv-bridge по HTTP host.docker.internal:PORT ...
 * без токена, слушает только 127.0.0.1"). The trading circuit never depends on this being up
 * (7: "Мостик недоступен → strategy пишет «TradingView недоступен» и работает дальше").
 */
const app = express();
app.use(express.json());

app.get('/health', async (req, res) => {
  res.json(await healthCheck());
});

app.use(drawRoute);
app.use(watchlistRoute);
app.use(screenshotRoute);

const PORT = parseInt(process.env.PORT || '3010', 10);
app.listen(PORT, '127.0.0.1', () => {
  console.log(`tv-bridge listening on http://127.0.0.1:${PORT}`);
});
