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

export interface AccountSummary {
  id: string;
  exchange: string;
  tradable: boolean;
}

export interface BalanceResponse {
  accountId: string;
  asset: string | null;
  balance: string | null;
  equity: string | null;
  unrealizedPnl: string | null;
  fetchedAt: number;
  stale: boolean;
  error: string | null;
}

export interface ExchangePosition {
  symbol: string;
  side: string;
  qty: string;
  avgPrice: string;
  markPrice: string;
  unrealizedPnl: string;
  leverage: number | null;
  liquidationPrice: string | null;
}

export interface LivePositionsResponse {
  accountId: string;
  positions: ExchangePosition[];
  error: string | null;
}

export interface StrategyState {
  phase: 'IDLE' | 'PROMPT_PENDING' | 'SEARCHING' | 'PROPOSALS_PENDING';
  promptMessageId: number | null;
  promptSentAt: number | null;
  pending: Proposal[];
}

export interface Proposal {
  proposalId: string;
  symbol: string;
  side: string;
  entryPrice: string;
  slPrice: string;
  tpPrice: string;
  rationale: string;
  status: string;
  createdAt: number;
  decidedAt: number | null;
  tgMessageId: number | null;
}

export interface AccountLegPreview {
  accountId: string;
  approved: boolean;
  qty: string | null;
  riskUsd: string | null;
  rejectReason: string | null;
}

export interface Position {
  id: number;
  positionId: string;
  accountId: string;
  exchange: string;
  symbol: string;
  side: string;
  quantity: string;
  entryPrice: string;
  slPrice: string | null;
  tpPrice: string | null;
  plannedRiskAmount: string | null;
  status: string;
  openedAt: number | null;
}

export interface ExecutionLegResult {
  accountId: string;
  success: boolean;
  filledQty: string | null;
  avgPrice: string | null;
  slPrice: string | null;
  tpPrice: string | null;
  riskUsd: string | null;
  stopVerified: boolean;
  errorReason: string | null;
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

  async getState(): Promise<StrategyState> {
    const resp = await this.client.get('/api/v1/strategy/state');
    return resp.data;
  }

  async setState(phase: string, promptMessageId: number | null, promptSentAt: number | null): Promise<StrategyState> {
    const resp = await this.client.put('/api/v1/strategy/state', { phase, promptMessageId, promptSentAt });
    return resp.data;
  }

  async createProposal(symbol: string, entryPrice: string, slPrice: string, tpPrice: string, rationale: string) {
    const resp = await this.client.post<{ proposalId: string; accounts: AccountLegPreview[] }>('/api/v1/strategy/proposals', {
      symbol, entryPrice, slPrice, tpPrice, rationale,
    });
    return resp.data;
  }

  async getProposal(id: string): Promise<Proposal> {
    const resp = await this.client.get(`/api/v1/strategy/proposals/${id}`);
    return resp.data;
  }

  async confirmProposal(id: string) {
    const resp = await this.client.post<{ proposalId: string; status: string; accounts: ExecutionLegResult[]; abortReason: string | null }>(
      `/api/v1/strategy/proposals/${id}/confirm`,
    );
    return resp.data;
  }

  async rejectProposal(id: string): Promise<Proposal> {
    const resp = await this.client.post(`/api/v1/strategy/proposals/${id}/reject`);
    return resp.data;
  }

  async closeAll(account: string, symbol?: string) {
    const resp = await this.client.post<{ ok: boolean; message: string }>('/api/v1/trade/close-all', { account, symbol });
    return resp.data;
  }

  async breakeven(positionId: number) {
    const resp = await this.client.post<{ ok: boolean; message: string }>(`/api/v1/positions/${positionId}/breakeven`);
    return resp.data;
  }

  async moveStop(positionId: number, type: 'sl' | 'tp', price: string) {
    const resp = await this.client.post<{ ok: boolean; message: string }>(`/api/v1/positions/${positionId}/stops`, { type, price });
    return resp.data;
  }

  async banSymbol(symbol: string, reason: string): Promise<void> {
    await this.client.post('/api/v1/strategy/blacklist', { symbol, reason });
  }

  async clearAutoPause(accountId: string) {
    const resp = await this.client.post<{ accountId: string; wasPaused: boolean; autoPaused: boolean }>(
      `/api/v1/accounts/${accountId}/auto-pause/clear`,
    );
    return resp.data;
  }

  async getOpenPositions(account?: string, symbol?: string): Promise<Position[]> {
    const resp = await this.client.get('/api/v1/trade/positions', { params: { account, symbol } });
    return resp.data;
  }

  async getAccounts(): Promise<AccountSummary[]> {
    const resp = await this.client.get('/api/v1/accounts');
    return resp.data;
  }

  async getBalance(accountId: string): Promise<BalanceResponse> {
    const resp = await this.client.get(`/api/v1/accounts/${accountId}/balance`, { params: { refresh: true } });
    return resp.data;
  }

  async getLivePositions(accountId: string): Promise<LivePositionsResponse> {
    const resp = await this.client.get(`/api/v1/accounts/${accountId}/live-positions`);
    return resp.data;
  }

  async getNotifications(after: number) {
    const resp = await this.client.get<Array<{ id: number; kind: string; accountId: string | null; symbol: string | null; message: string; createdAt: number }>>(
      '/api/v1/notifications',
      { params: { after } },
    );
    return resp.data;
  }
}

/** trading's JSON already comes back camelCase (Jackson default) — kept for explicitness/typing only. */
function camelizeSettings(raw: any): StrategySettings {
  return raw;
}
