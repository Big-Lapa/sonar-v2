import { AccountLegPreview, ExecutionLegResult } from '../trading-client/trading-api.service';

export function formatProposalMessage(
  symbol: string,
  entry: string,
  sl: string,
  tp: string,
  rr: number,
  rationale: string,
  legs: AccountLegPreview[],
  costUsd: number,
): string {
  const slPct = (((parseFloat(sl) - parseFloat(entry)) / parseFloat(entry)) * 100).toFixed(2);
  const tpPct = (((parseFloat(tp) - parseFloat(entry)) / parseFloat(entry)) * 100).toFixed(2);
  const lines = [
    `📈 ${symbol} LONG`,
    `Вход: ${entry}  SL: ${sl} (${slPct}%)  TP: ${tp} (+${tpPct}%)  R:R ${rr.toFixed(2)}`,
    '',
    ...legs.map((l) => (l.approved ? `${l.accountId}: риск $${parseFloat(l.riskUsd!).toFixed(2)}, qty ${l.qty}` : `${l.accountId}: отклонён — ${l.rejectReason}`)),
    '',
    rationale,
    '',
    `стоимость прогона: $${costUsd.toFixed(4)}`,
  ];
  return lines.join('\n');
}

export function formatExecutionReport(legs: ExecutionLegResult[]): string {
  return legs
    .map((l) => {
      if (!l.success) return `${l.accountId} ✗ отклонён: ${l.errorReason}`;
      const stopNote = l.stopVerified ? '' : ' ⚠️ СТОП НЕ ПОДТВЕРЖДЁН';
      return `${l.accountId} ✓ ${l.filledQty} @ ${l.avgPrice} · SL ${l.slPrice} · TP ${l.tpPrice} · риск $${parseFloat(l.riskUsd ?? '0').toFixed(2)}${stopNote}`;
    })
    .join('\n');
}
