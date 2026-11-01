/** per-model $/1M token rates. Web search $10/1000 regardless of model. */
export interface UsageCost {
  inputTokens: number;
  outputTokens: number;
  webSearchRequests: number;
  costUsd: number | undefined;
}

// keep in sync: admin.html LLM_MODELS, StrategySettingsController.ALLOWED_MODELS (Java), candidate-search.service.ts ADAPTIVE_THINKING_MODELS (TS)
const RATES: Record<string, { input: number; output: number }> = {
  'claude-opus-5': { input: 15, output: 75 },
  'claude-sonnet-5': { input: 3, output: 15 },
  'claude-haiku-4-5-20251001': { input: 1, output: 5 },
};

const SONNET_5_PROMO_ENDS_UTC = Date.UTC(2026, 7, 31, 23, 59, 59); // month is 0-indexed: 7 = August
const SONNET_5_PROMO_RATE = { input: 2, output: 10 };

export function computeCost(
  inputTokens: number,
  outputTokens: number,
  webSearchRequests: number,
  model: string,
): UsageCost {
  const webSearchCost = (webSearchRequests / 1000) * 10;

  let rate = RATES[model];
  if (!rate) {
    console.warn(`model-pricing: no rate table entry for model "${model}", costUsd will be undefined`);
    return { inputTokens, outputTokens, webSearchRequests, costUsd: undefined };
  }
  if (model === 'claude-sonnet-5' && Date.now() < SONNET_5_PROMO_ENDS_UTC) {
    rate = SONNET_5_PROMO_RATE;
  }

  const costUsd = (inputTokens / 1_000_000) * rate.input + (outputTokens / 1_000_000) * rate.output + webSearchCost;
  return { inputTokens, outputTokens, webSearchRequests, costUsd };
}
