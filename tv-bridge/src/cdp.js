const CDP = require('chrome-remote-interface');

const CDP_PORT = parseInt(process.env.TV_CDP_PORT || '9222', 10);

let client = null;
let connecting = null;

/**
 * TradingView Desktop exposes a Chrome DevTools Protocol debug port (same one the coin-analysis
 * skill's tooling uses — verified live against the real internal chart-widget API before writing
 * this file). We reconnect lazily; TradingView Desktop is not always
 * running, and a dead connection must not crash the bridge — callers see a clear error instead.
 */
async function getClient() {
  if (client) return client;
  if (connecting) return connecting;
  connecting = (async () => {
    const targets = await CDP.List({ port: CDP_PORT });
    const target = targets.find((t) => t.type === 'page' && /tradingview\.com/i.test(t.url));
    if (!target) {
      throw new Error('no TradingView page target found on CDP — is TradingView Desktop running with --remote-debugging-port?');
    }
    const c = await CDP({ port: CDP_PORT, target: target.id });
    await c.Runtime.enable();
    await c.Page.enable();
    c.on('disconnect', () => { client = null; });
    client = c;
    return c;
  })();
  try {
    return await connecting;
  } finally {
    connecting = null;
  }
}

/** Evaluate JS in the TradingView page. Wrap async work in an IIFE returning a Promise — awaited. */
async function evaluate(expression) {
  const c = await getClient();
  const result = await c.Runtime.evaluate({
    expression,
    awaitPromise: true,
    returnByValue: true,
    timeout: 15000,
  });
  if (result.exceptionDetails) {
    const msg = result.exceptionDetails.exception?.description || JSON.stringify(result.exceptionDetails);
    throw new Error(`page evaluation failed: ${msg}`);
  }
  return result.result.value;
}

/** PNG screenshot of the current viewport (the chart, since we don't touch window chrome). */
async function screenshotPng() {
  const c = await getClient();
  const { data } = await c.Page.captureScreenshot({ format: 'png' });
  return Buffer.from(data, 'base64');
}

async function healthCheck() {
  try {
    const symbol = await evaluate(
      "window.TradingViewApi._activeChartWidgetWV.value().symbol()",
    );
    return { connected: true, symbol };
  } catch (e) {
    return { connected: false, error: e.message };
  }
}

module.exports = { evaluate, screenshotPng, healthCheck };
