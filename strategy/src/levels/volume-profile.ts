import { Kline } from '../klines/klines.types';

export interface VolumeBin {
  price: number;
  volume: number;
}

export interface VolumeProfile {
  bins: VolumeBin[];
  hvn: VolumeBin[]; // top-10 by volume
}

/**
 * 4.3.2 — 1:1 port of the coin-analysis skill's reference algorithm (section 3): daily bars,
 * bin = 0.5% of price (or 0.005 absolute below $1), each bar's volume spread evenly across its
 * high-low range, top-10 bins by volume = HVN.
 */
export function buildVolumeProfile(dailyBars: Kline[], referencePrice: number): VolumeProfile {
  const bin = referencePrice < 1 ? 0.005 : referencePrice * 0.005;
  const buckets = new Map<string, number>();

  const addVol = (price: number, vol: number) => {
    const key = (Math.round(price / bin) * bin).toFixed(8);
    buckets.set(key, (buckets.get(key) ?? 0) + vol);
  };

  for (const b of dailyBars) {
    const steps = Math.max(1, Math.round((b.high - b.low) / (bin / 2)));
    const vps = b.volume / steps;
    for (let i = 0; i <= steps; i++) {
      addVol(b.low + ((b.high - b.low) * i) / steps, vps);
    }
  }

  const bins: VolumeBin[] = [...buckets.entries()]
    .map(([p, v]) => ({ price: parseFloat(p), volume: v }))
    .sort((a, b) => a.price - b.price);

  const hvn = [...bins].sort((a, b) => b.volume - a.volume).slice(0, 10);

  return { bins, hvn };
}

/** LVN: thin bins near the current price (transit zones — price doesn't linger there). */
export function findLvnNear(profile: VolumeProfile, currentPrice: number, windowPct = 0.15, take = 3): VolumeBin[] {
  const lo = currentPrice * (1 - windowPct);
  const hi = currentPrice * (1 + windowPct);
  return [...profile.bins]
    .filter((b) => b.price >= lo && b.price <= hi)
    .sort((a, b) => a.volume - b.volume)
    .slice(0, take);
}
