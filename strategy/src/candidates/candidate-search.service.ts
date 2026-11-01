import { Injectable, Logger } from '@nestjs/common';
import Anthropic from '@anthropic-ai/sdk';
import { computeCost, UsageCost } from './model-pricing';

export interface Candidate {
  symbol: string;
  rationale: string;
}

export interface SearchResult {
  candidates: Candidate[];
  cost: UsageCost;
}

const MAX_WEB_SEARCHES = 12;

// keep in sync: admin.html LLM_MODELS, StrategySettingsController.ALLOWED_MODELS (Java), model-pricing.ts RATES (TS)
const ADAPTIVE_THINKING_MODELS = new Set(['claude-opus-5', 'claude-sonnet-5']);

/**
 * one Sonnet 5 call over the whole prefiltered universe. The model picks symbols with a
 * bullish news backdrop where price hasn't moved yet — it NEVER computes price/qty/levels (1st
 * invariant), only names symbols (validated against the allowlist) and writes Russian rationale.
 */
@Injectable()
export class CandidateSearchService {
  private readonly logger = new Logger(CandidateSearchService.name);
  private readonly client = new Anthropic();

  async search(universe: string[], maxCandidates: number, model: string): Promise<SearchResult> {
    const response = await this.client.messages.create({
      model,
      max_tokens: 4096,
      ...(ADAPTIVE_THINKING_MODELS.has(model) ? { thinking: { type: 'adaptive' } as any } : {}),
      tools: [
        // allowed_callers pinned to direct-only: some models (e.g. Haiku 4.5) don't support
        // programmatic tool calling, and we never invoke this tool that way regardless of model.
        { type: 'web_search_20260209', name: 'web_search', max_uses: MAX_WEB_SEARCHES, allowed_callers: ['direct'] } as any,
        {
          name: 'report_candidates',
          description:
            'Report the final shortlist of candidate symbols with bullish news that price has not caught up to yet.',
          input_schema: {
            type: 'object',
            properties: {
              candidates: {
                type: 'array',
                items: {
                  type: 'object',
                  properties: {
                    symbol: { type: 'string', description: 'Must be exactly one symbol from the provided universe list.' },
                    rationale: { type: 'string', description: '1-3 sentences in Russian. No prices, quantities, or levels.' },
                  },
                  required: ['symbol', 'rationale'],
                  additionalProperties: false,
                },
              },
            },
            required: ['candidates'],
            additionalProperties: false,
          },
          strict: true,
        } as any,
      ],
      messages: [{ role: 'user', content: buildPrompt(universe, maxCandidates) }],
    });

    this.checkForFailedSearches(response.content);

    const toolUse = response.content.find(
      (b: any) => b.type === 'tool_use' && b.name === 'report_candidates',
    ) as any;

    const rawCandidates: Candidate[] = toolUse?.input?.candidates ?? [];
    const universeSet = new Set(universe);
    const validated = rawCandidates
      .filter((c) => {
        const ok = universeSet.has(c.symbol);
        if (!ok) this.logger.warn(`model returned symbol outside allowlist, ignored: ${c.symbol}`);
        return ok;
      })
      .slice(0, maxCandidates);

    const usage: any = response.usage;
    const cost = computeCost(
      usage.input_tokens ?? 0,
      usage.output_tokens ?? 0,
      usage.server_tool_use?.web_search_requests ?? 0,
      model,
    );

    return { candidates: validated, cost };
  }

  /** a failed web_search_tool_result arrives HTTP 200 with `content` as an object, not an array. */
  private checkForFailedSearches(content: any[]): void {
    for (const block of content) {
      if (block.type === 'web_search_tool_result' && !Array.isArray(block.content)) {
        this.logger.warn(`web_search_tool_result reported an error: ${JSON.stringify(block.content)}`);
      }
    }
  }
}

function buildPrompt(universe: string[], maxCandidates: number): string {
  return [
    `Symbol universe (already filtered by volume/volatility/blacklist): ${universe.join(', ')}`,
    '',
    `Find up to ${maxCandidates} coins from THIS list with a clearly bullish news background (partnerships, listings, ` +
      'institutional interest, major integrations) whose price HAS NOT YET reacted adequately ' +
      '(flat or falling despite good news, or rising only at the level of general market beta).',
    '',
    'IMPORTANT: you only pick symbols from the list above and write the rationale as text. You do NOT calculate price, ' +
      'quantity, stop-loss, take-profit or any other numbers — deterministic code does that separately. ' +
      'The rationale is 1-3 sentences in English, without specific prices or levels.',
    '',
    `When you finish the search, call report_candidates with the final list (may be fewer than ${maxCandidates} ` +
      'if there are fewer suitable candidates; you may return an empty list if nothing fits).',
  ].join('\n');
}
