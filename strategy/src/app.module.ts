import { Module } from '@nestjs/common';
import { ScheduleModule } from '@nestjs/schedule';
import { HealthController } from './health.controller';
import { TradingApiService } from './trading-client/trading-api.service';
import { TelegramClientService } from './telegram/telegram-client.service';
import { TelegramBotService } from './telegram/telegram-bot.service';

/**
 * Manual multi-exchange management mode: the LLM candidate-search pipeline (klines/levels/
 * universe/candidates/search — Anthropic-API-driven) is unwired here, not deleted. Nothing in
 * this module reaches it anymore, so no automatic prompting and no API spend happens; the source
 * stays on disk if this ever needs to come back. TelegramBotService is now purely manual ops
 * (/status /positions /closeall /be /move /ban /unpause) plus notification relay.
 */
@Module({
  imports: [ScheduleModule.forRoot()],
  controllers: [HealthController],
  providers: [
    TradingApiService,
    TelegramClientService,
    TelegramBotService,
  ],
})
export class AppModule {}
