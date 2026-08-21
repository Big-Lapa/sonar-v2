/** 4.5: Sonnet 5 promo pricing through 2026-08-31 ($2/$10 per 1M in/out), $3/$15 after. Web search $10/1000. */
export interface UsageCost {
  inputTokens: number;
  outputTokens: number;
  webSearchRequests: number;
  costUsd: number;
}

export function computeCost(inputTokens: number, outputTokens: number, webSearchRequests: number): UsageCost {
  const promoActive = Date.now() < Date.UTC(2026, 7, 31, 23, 59, 59); // month is 0-indexed: 7 = August
  const inputRate = promoActive ? 2 : 3;
  const outputRate = promoActive ? 10 : 15;
  const costUsd =
    (inputTokens / 1_000_000) * inputRate +
    (outputTokens / 1_000_000) * outputRate +
    (webSearchRequests / 1000) * 10;
  return { inputTokens, outputTokens, webSearchRequests, costUsd };
}
