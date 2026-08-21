import { Injectable, Logger } from '@nestjs/common';
import { PrefilterService } from '../universe/prefilter.service';
import { TradingApiService } from '../trading-client/trading-api.service';
import { CandidateSearchService } from '../candidates/candidate-search.service';
import { LevelsService } from '../levels/levels.service';

export interface SearchRunResult {
  universeSize: number;
  candidatesFromLlm: number;
  proposals: Array<{
    symbol: string;
    entry: number;
    sl: number;
    tp: number;
    rr: number;
    slSource: string;
    tpSource: string;
    rationale: string;
  }>;
  rejected: Array<{ symbol: string; reason: string }>;
  costUsd: number;
}

/**
 * Stage 5: universe -> setup gates -> LLM -> levels -> setup gates -> log. No Telegram here
 * (stage 6) — this is the pipeline itself, callable directly so stage 6 wires it behind the
 * PROMPT_PENDING -> SEARCHING transition without rewriting any of this.
 */
@Injectable()
export class SearchOrchestratorService {
  private readonly logger = new Logger(SearchOrchestratorService.name);

  constructor(
    private readonly prefilter: PrefilterService,
    private readonly tradingApi: TradingApiService,
    private readonly candidateSearch: CandidateSearchService,
    private readonly levels: LevelsService,
  ) {}

  async run(): Promise<SearchRunResult> {
    const settings = await this.tradingApi.getSettings();
    const universe = await this.prefilter.buildUniverse();
    this.logger.log(`universe ready: ${universe.length} symbols`);

    if (universe.length === 0) {
      return { universeSize: 0, candidatesFromLlm: 0, proposals: [], rejected: [], costUsd: 0 };
    }

    const { candidates, cost } = await this.candidateSearch.search(universe, settings.maxCandidates, settings.llmModel);
    this.logger.log(
      `LLM returned ${candidates.length} candidates, cost=$${cost.costUsd.toFixed(4)} ` +
        `(in=${cost.inputTokens} out=${cost.outputTokens} searches=${cost.webSearchRequests})`,
    );

    const proposals: SearchRunResult['proposals'] = [];
    const rejected: SearchRunResult['rejected'] = [];

    const gateInputs = {
      minSlDistancePct: parseFloat(settings.minSlDistancePct),
      minSlCostMult: parseFloat(settings.minSlCostMult),
      maxSlDistancePct: parseFloat(settings.maxSlDistancePct),
      minRr: parseFloat(settings.minRr),
    };

    for (const candidate of candidates) {
      try {
        const result = await this.levels.computeLongLevels(candidate.symbol, gateInputs);
        if (!result.ok) {
          this.logger.log(`${candidate.symbol}: setup gate failed — ${result.reason}`);
          rejected.push({ symbol: candidate.symbol, reason: result.reason });
          continue;
        }
        const l = result.levels;
        this.logger.log(
          `${candidate.symbol}: entry=${l.entry} sl=${l.sl} (${l.slSource}) tp=${l.tp} (${l.tpSource}) ` +
            `rr=${l.rr.toFixed(2)} distSL=${(l.distSlPct * 100).toFixed(2)}% — ${candidate.rationale}`,
        );
        proposals.push({
          symbol: candidate.symbol,
          entry: l.entry,
          sl: l.sl,
          tp: l.tp,
          rr: l.rr,
          slSource: l.slSource,
          tpSource: l.tpSource,
          rationale: candidate.rationale,
        });
      } catch (e: any) {
        this.logger.error(`${candidate.symbol}: levels computation failed: ${e.message}`);
        rejected.push({ symbol: candidate.symbol, reason: `ошибка расчёта уровней: ${e.message}` });
      }
    }

    return { universeSize: universe.length, candidatesFromLlm: candidates.length, proposals, rejected, costUsd: cost.costUsd };
  }
}
