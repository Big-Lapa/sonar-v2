import { SwingLevel } from './swing';
import { VolumeBin } from './volume-profile';

export interface TradeLevels {
  entry: number;
  sl: number;
  tp: number;
  slSource: string;
  tpSource: string;
  rr: number;
  distSlPct: number;
}

export interface SetupGateInputs {
  minSlDistancePct: number;
  minSlCostMult: number;
  maxSlDistancePct: number;
  minRr: number;
  roundTripCostPct: number; // taker-fee round trip estimate, not in strategy_settings — see levels.service
}

export type PickResult = { ok: true; levels: TradeLevels } | { ok: false; reason: string };

/**
 * Level selection + setup gates. LONG only:
 *   sl = nearest support/LVN below entry that clears the minimum distance gate
 *   tp = nearest HVN/resistance above entry that ALSO clears the same minimum distance gate
 * Gates: distSL >= max(minSlDistancePct, roundTripCost * minSlCostMult); distSL <= maxSlDistancePct;
 * RR = (tp-entry)/(entry-sl) >= minRr.
 *
 * The TP-side minimum distance wasn't in the original design (only SL's minimum was gated),
 * but live testing surfaced why it has to be symmetric: on a low-volatility symbol (TRXUSDT) the
 * "nearest resistance above entry" was a swing high sitting fractions of a percent away, giving
 * R:R ≈ 0.03 — a target so close it isn't a target. Made symmetric on purpose rather than
 * silently reinterpreting the original rule.
 */
export function pickLongLevels(
  entry: number,
  swingLevels: SwingLevel[],
  hvn: VolumeBin[],
  lvn: VolumeBin[],
  gates: SetupGateInputs,
): PickResult {
  const minSlDist = Math.max(gates.minSlDistancePct, gates.roundTripCostPct * gates.minSlCostMult);

  const slCandidates = [
    ...swingLevels.filter((l) => l.kind === 'support').map((l) => ({ price: l.price, source: `swing support (${l.touches}x)` })),
    ...lvn.map((b) => ({ price: b.price, source: 'LVN' })),
  ]
    .filter((c) => c.price < entry)
    .sort((a, b) => b.price - a.price); // nearest to entry first

  const slPick = slCandidates.find((c) => (entry - c.price) / entry >= minSlDist);
  if (!slPick) {
    return { ok: false, reason: `no support/LVN below entry beyond the minimum distance ${(minSlDist * 100).toFixed(2)}%` };
  }
  const distSlPct = (entry - slPick.price) / entry;
  if (distSlPct > gates.maxSlDistancePct) {
    return { ok: false, reason: `nearest valid SL is too far: ${(distSlPct * 100).toFixed(2)}% > ${(gates.maxSlDistancePct * 100).toFixed(2)}%` };
  }

  const tpCandidates = [
    ...swingLevels.filter((l) => l.kind === 'resistance').map((l) => ({ price: l.price, source: `swing resistance (${l.touches}x)` })),
    ...hvn.map((b) => ({ price: b.price, source: 'HVN' })),
  ]
    .filter((c) => c.price > entry)
    .sort((a, b) => a.price - b.price); // nearest to entry first

  const tpPick = tpCandidates.find((c) => (c.price - entry) / entry >= minSlDist);
  if (!tpPick) {
    return { ok: false, reason: `no HVN/swing resistance above entry beyond the minimum distance ${(minSlDist * 100).toFixed(2)}%` };
  }

  const rr = (tpPick.price - entry) / (entry - slPick.price);
  if (rr < gates.minRr) {
    return { ok: false, reason: `R:R ${rr.toFixed(2)} < minimum ${gates.minRr}` };
  }

  return {
    ok: true,
    levels: { entry, sl: slPick.price, tp: tpPick.price, slSource: slPick.source, tpSource: tpPick.source, rr, distSlPct },
  };
}
