import { Module } from '@nestjs/common';
import { HealthController } from './health.controller';
import { BinanceKlinesService } from './klines/binance-klines.service';
import { LevelsService } from './levels/levels.service';
import { ContractsService } from './universe/contracts.service';
import { PrefilterService } from './universe/prefilter.service';
import { TradingApiService } from './trading-client/trading-api.service';
import { CandidateSearchService } from './candidates/candidate-search.service';
import { SearchOrchestratorService } from './search/search-orchestrator.service';
import { SearchController } from './search/search.controller';

@Module({
  imports: [],
  controllers: [HealthController, SearchController],
  providers: [
    BinanceKlinesService,
    LevelsService,
    ContractsService,
    PrefilterService,
    TradingApiService,
    CandidateSearchService,
    SearchOrchestratorService,
  ],
})
export class AppModule {}
