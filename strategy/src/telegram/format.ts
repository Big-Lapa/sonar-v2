import { AccountLegPreview, ExecutionLegResult } from '../trading-client/trading-api.service';
import { DEFAULT_LANG, hasKey, Lang, t } from '../i18n';

/** A leg's failure text: translated by its backend code when known, else the stored English reason. */
function reasonText(lang: Lang, code: string | null | undefined, params: Record<string, string> | null | undefined, fallback: string | null): string {
  return code && hasKey(lang, `msg.${code}`) ? t(lang, `msg.${code}`, params ?? {}) : (fallback ?? '');
}

export function formatProposalMessage(
  symbol: string,
  entry: string,
  sl: string,
  tp: string,
  rr: number,
  rationale: string,
  legs: AccountLegPreview[],
  costUsd: number | undefined,
  lang: Lang = DEFAULT_LANG,
): string {
  const slPct = (((parseFloat(sl) - parseFloat(entry)) / parseFloat(entry)) * 100).toFixed(2);
  const tpPct = (((parseFloat(tp) - parseFloat(entry)) / parseFloat(entry)) * 100).toFixed(2);
  const lines = [
    `📈 ${symbol} LONG`,
    t(lang, 'fmt.entry', { entry, sl, slPct, tp, tpPct, rr: rr.toFixed(2) }),
    '',
    ...legs.map((l) =>
      l.approved
        ? t(lang, 'fmt.leg_ok', { account: l.accountId, risk: parseFloat(l.riskUsd!).toFixed(2), qty: l.qty })
        : t(lang, 'fmt.leg_rejected', {
            account: l.accountId,
            reason: reasonText(lang, l.rejectCode, l.rejectParams, l.rejectReason),
          }),
    ),
    '',
    rationale,
    '',
    t(lang, 'fmt.cost', { cost: costUsd !== undefined ? '$' + costUsd.toFixed(4) : t(lang, 'fmt.cost_na') }),
  ];
  return lines.join('\n');
}

export function formatExecutionReport(legs: ExecutionLegResult[], lang: Lang = DEFAULT_LANG): string {
  return legs
    .map((l) => {
      if (!l.success) {
        return t(lang, 'fmt.exec_rejected', {
          account: l.accountId,
          reason: reasonText(lang, l.errorCode, l.errorParams, l.errorReason),
        });
      }
      const stopNote = l.stopVerified ? '' : t(lang, 'fmt.stop_unverified');
      return (
        t(lang, 'fmt.exec_ok', {
          account: l.accountId,
          qty: l.filledQty,
          price: l.avgPrice,
          sl: l.slPrice,
          tp: l.tpPrice,
          risk: parseFloat(l.riskUsd ?? '0').toFixed(2),
        }) + stopNote
      );
    })
    .join('\n');
}
