import { Injectable, Logger } from '@nestjs/common';
import axios from 'axios';
import { ContractsService } from './contracts.service';
import { TradingApiService, StrategySettings } from '../trading-client/trading-api.service';
import { BinanceKlinesService } from '../klines/binance-klines.service';

interface Ticker24h {
  symbol: string;
  quoteVolume: string;
  priceChangePercent: string;
}

/**
 * universe = Binance perps ∩ BingX contracts − open positions (any account) −
 * trading.symbol_blacklist − 24h volume < min − |24h move| > max − |7d move| > max. All cuts
 * happen BEFORE the LLM ever sees the list — one model call on the whole survivor set.
 */
@Injectable()
export class PrefilterService {
  private readonly logger = new Logger(PrefilterService.name);

  constructor(
    private readonly contracts: ContractsService,
    private readonly tradingApi: TradingApiService,
    private readonly klines: BinanceKlinesService,
  ) {}

  async buildUniverse(): Promise<string[]> {
    const [symbols, blacklist, slots, tickers] = await Promise.all([
      this.contracts.getUniverseSymbols(),
      this.tradingApi.getBlacklist(),
      this.tradingApi.getAccountSlots(),
      this.get24hTickers(),
    ]);
    const settings = await this.tradingApi.getSettings();

    const blacklistSet = new Set(blacklist);
    const openSymbols = new Set(slots.flatMap((s) => s.openSymbols));
    const minVolume = parseFloat(settings.min24hVolumeUsd);
    const maxMove24h = parseFloat(settings.maxMove24hPct);

    let survivors = symbols.filter((s) => !openSymbols.has(s) && !blacklistSet.has(s));
    this.logger.log(`universe after open-positions/blacklist cut: ${survivors.length}/${symbols.length}`);

    survivors = survivors.filter((s) => {
      const t = tickers.get(s);
      if (!t) return false;
      return parseFloat(t.quoteVolume) >= minVolume && Math.abs(parseFloat(t.priceChangePercent) / 100) <= maxMove24h;
    });
    this.logger.log(`universe after 24h volume/move cut: ${survivors.length}`);

    survivors = await this.filter7dMove(survivors, parseFloat(settings.maxMove7dPct));
    this.logger.log(`universe after 7d move cut: ${survivors.length}`);

    return survivors;
  }

  private async get24hTickers(): Promise<Map<string, Ticker24h>> {
    const resp = await axios.get('https://fapi.binance.com/fapi/v1/ticker/24hr', { timeout: 15_000 });
    const map = new Map<string, Ticker24h>();
    for (const t of resp.data as Ticker24h[]) {
      map.set(t.symbol, t);
    }
    return map;
  }

  /** Only run on the (already narrowed) 24h survivors — one 1d-klines call per symbol, disk-cached. */
  private async filter7dMove(symbols: string[], maxMove7d: number): Promise<string[]> {
    const results = await Promise.all(
      symbols.map(async (symbol) => {
        try {
          const bars = await this.klines.getKlines(symbol, '1d', 8);
          if (bars.length < 8) return null;
          const change = (bars[bars.length - 1].close - bars[0].close) / bars[0].close;
          return Math.abs(change) <= maxMove7d ? symbol : null;
        } catch {
          return null; // no data → conservatively excluded, not a crash
        }
      }),
    );
    return results.filter((s): s is string => s !== null);
  }
}
