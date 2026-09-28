import { Controller, Headers, Post, UnauthorizedException } from '@nestjs/common';
import { createHash, timingSafeEqual } from 'crypto';
import { SearchOrchestratorService } from './search-orchestrator.service';

/**
 * Stage 5 trigger — no Telegram yet (stage 6 wires PROMPT_PENDING -> SEARCHING to this same
 * orchestrator). Manual trigger for now:
 * `curl -X POST -H "X-Admin-Token: $ADMIN_TOKEN" http://localhost:3000/internal/search/run`.
 *
 * Gated by the same ADMIN_TOKEN as trading's /api/v1: each run spends Anthropic API money, and
 * without a custom header any web page open on the host could fire it (a bodyless cross-site POST
 * needs no CORS preflight). Unset token → always 401.
 */
@Controller('internal/search')
export class SearchController {
  constructor(private readonly orchestrator: SearchOrchestratorService) {}

  @Post('run')
  run(@Headers('x-admin-token') token?: string) {
    if (!tokenMatches(token)) throw new UnauthorizedException('invalid admin token');
    return this.orchestrator.run();
  }
}

function tokenMatches(provided?: string): boolean {
  const expected = process.env.ADMIN_TOKEN ?? '';
  if (!expected || !provided) return false;
  const a = createHash('sha256').update(provided).digest();
  const b = createHash('sha256').update(expected).digest();
  return timingSafeEqual(a, b);
}
