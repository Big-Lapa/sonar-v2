import { Injectable } from '@nestjs/common';
import axios, { AxiosInstance } from 'axios';

export interface StrategySettings {
  minSlDistancePct: string;
  minSlCostMult: string;
  maxSlDistancePct: string;
  minRr: string;
  min24hVolumeUsd: string;
  maxMove24hPct: string;
  maxMove7dPct: string;
  maxCandidates: number;
  llmModel: string;
  alwaysDrawTv: boolean;
  proposalTtlMs: number;
  holdReminderDays: number;
  searchCronHours: string;
}

export interface AccountSlot {
  accountId: string;
  tradable: boolean;
  freeSlots: number;
  maxOpenPositions: number;
  equity: string | null;
  autoPaused: boolean;
  autoPauseCause: string | null;
  openSymbols: string[];
}

/** REST client to `trading` (4.8) — X-Admin-Token on every call. */
@Injectable()
export class TradingApiService {
  private readonly client: AxiosInstance;

  constructor() {
    this.client = axios.create({
      baseURL: process.env.TRADING_URL ?? 'http://trading:8080',
      timeout: 10_000,
      headers: { 'X-Admin-Token': process.env.ADMIN_TOKEN ?? '' },
    });
  }

  async getSettings(): Promise<StrategySettings> {
    const resp = await this.client.get('/api/v1/strategy/settings');
    return camelizeSettings(resp.data);
  }

  async getBlacklist(): Promise<string[]> {
    const resp = await this.client.get('/api/v1/strategy/blacklist');
    return (resp.data as Array<{ symbol: string }>).map((r) => r.symbol);
  }

  async getAccountSlots(): Promise<AccountSlot[]> {
    const resp = await this.client.get('/api/v1/accounts/slots');
    return resp.data;
  }
}

/** trading's JSON already comes back camelCase (Jackson default) — kept for explicitness/typing only. */
function camelizeSettings(raw: any): StrategySettings {
  return raw;
}
