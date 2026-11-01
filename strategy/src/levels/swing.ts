import { Kline } from '../klines/klines.types';

export interface SwingLevel {
  price: number;
  kind: 'resistance' | 'support'; // from swing highs / swing lows respectively
  touches: number;
}

/**
 * Local extremum by raw bars (lookback 3 — a strict local max/min over a 7-bar window),
 * then clustered — prices within 0.3% of each other collapse into one level, weight = touch count.
 */
export function findSwingLevels(bars: Kline[], lookback = 3, clusterPct = 0.003): SwingLevel[] {
  const highs: number[] = [];
  const lows: number[] = [];

  for (let i = lookback; i < bars.length - lookback; i++) {
    const window = bars.slice(i - lookback, i + lookback + 1);
    const bar = bars[i];
    if (window.every((w) => bar.high >= w.high)) {
      highs.push(bar.high);
    }
    if (window.every((w) => bar.low <= w.low)) {
      lows.push(bar.low);
    }
  }

  return [...cluster(highs, 'resistance', clusterPct), ...cluster(lows, 'support', clusterPct)];
}

function cluster(prices: number[], kind: 'resistance' | 'support', clusterPct: number): SwingLevel[] {
  if (prices.length === 0) return [];
  const sorted = [...prices].sort((a, b) => a - b);
  const groups: number[][] = [];
  let current: number[] = [sorted[0]];

  for (let i = 1; i < sorted.length; i++) {
    const groupAvg = current.reduce((a, b) => a + b, 0) / current.length;
    if (Math.abs(sorted[i] - groupAvg) / groupAvg <= clusterPct) {
      current.push(sorted[i]);
    } else {
      groups.push(current);
      current = [sorted[i]];
    }
  }
  groups.push(current);

  return groups.map((g) => ({
    price: g.reduce((a, b) => a + b, 0) / g.length,
    kind,
    touches: g.length,
  }));
}
