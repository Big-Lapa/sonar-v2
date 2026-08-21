import { Injectable, Logger } from '@nestjs/common';
import axios from 'axios';
import { BinanceKlinesService } from '../klines/binance-klines.service';
import { findSwingLevels } from './swing';
import { buildVolumeProfile, findLvnNear } from './volume-profile';
import { PickResult, pickLongLevels, SetupGateInputs } from './pick-trade-levels';

/**
 * Binance USDT-M taker round-trip fee estimate (2 legs). Not a `strategy_settings` field (4.1
 * has no such column) — root CLAUDE.md's admin-backtest convention already pins Binance taker at
 * ~5bps/leg, so 2x5bps=0.1% round trip is the consistent default here too.
 */
const ROUND_TRIP_COST_PCT = 0.001;

export interface LevelsResult extends Omit<PickResult, never> {
  symbol: string;
}

@Injectable()
export class LevelsService {
  private readonly logger = new Logger(LevelsService.name);

  constructor(private readonly klines: BinanceKlinesService) {}

  async computeLongLevels(symbol: string, gates: Omit<SetupGateInputs, 'roundTripCostPct'>): Promise<PickResult> {
    const [bars4h, bars1d, price] = await Promise.all([
      this.klines.getKlines(symbol, '4h', 200),
      this.klines.getKlines(symbol, '1d', 300),
      this.getCurrentPrice(symbol),
    ]);

    const swingLevels = [...findSwingLevels(bars4h), ...findSwingLevels(bars1d)];
    const profile = buildVolumeProfile(bars1d, price);
    const lvn = findLvnNear(profile, price);

    return pickLongLevels(price, swingLevels, profile.hvn, lvn, { ...gates, roundTripCostPct: ROUND_TRIP_COST_PCT });
  }

  private async getCurrentPrice(symbol: string): Promise<number> {
    const resp = await axios.get('https://fapi.binance.com/fapi/v1/ticker/price', {
      params: { symbol },
      timeout: 5_000,
    });
    return parseFloat(resp.data.price);
  }
}
