import { Controller, Post } from '@nestjs/common';
import { SearchOrchestratorService } from './search-orchestrator.service';

/**
 * Stage 5 trigger — no Telegram yet (stage 6 wires PROMPT_PENDING -> SEARCHING to this same
 * orchestrator). Manual trigger for now: `curl -X POST http://localhost:3000/internal/search/run`.
 */
@Controller('internal/search')
export class SearchController {
  constructor(private readonly orchestrator: SearchOrchestratorService) {}

  @Post('run')
  run() {
    return this.orchestrator.run();
  }
}
