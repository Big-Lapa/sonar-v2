import { Injectable, Logger } from '@nestjs/common';
import axios from 'axios';
import * as fs from 'fs';
import * as path from 'path';
import { Kline } from './klines.types';

const INTERVAL_MS: Record<string, number> = {
  '4h': 4 * 60 * 60 * 1000,
  '1d': 24 * 60 * 60 * 1000,
};

/**
 * Binance REST klines for the perp (4.3): 4h x 200 (~33d, near structure), 1d x 300
 * (~10mo, macro + volume profile). Disk cache per symbol/interval, invalidated by the age of
 * the last cached bar's close time (not a fixed TTL) — a cache is stale exactly when a new
 * bar should already exist.
 */
@Injectable()
export class BinanceKlinesService {
  private readonly logger = new Logger(BinanceKlinesService.name);
  private readonly cacheDir = path.join(process.cwd(), 'data', 'klines-cache');

  async getKlines(symbol: string, interval: '4h' | '1d', limit: number): Promise<Kline[]> {
    const cached = this.readCache(symbol, interval);
    if (cached && cached.length >= limit && this.isFresh(cached, interval)) {
      return cached.slice(-limit);
    }
    const fresh = await this.fetch(symbol, interval, limit);
    this.writeCache(symbol, interval, fresh);
    return fresh;
  }

  private isFresh(bars: Kline[], interval: '4h' | '1d'): boolean {
    const last = bars[bars.length - 1];
    return Date.now() - last.closeTime < INTERVAL_MS[interval];
  }

  private async fetch(symbol: string, interval: '4h' | '1d', limit: number): Promise<Kline[]> {
    const resp = await axios.get('https://fapi.binance.com/fapi/v1/klines', {
      params: { symbol, interval, limit },
      timeout: 10_000,
    });
    return (resp.data as any[]).map((row) => ({
      openTime: row[0],
      open: parseFloat(row[1]),
      high: parseFloat(row[2]),
      low: parseFloat(row[3]),
      close: parseFloat(row[4]),
      volume: parseFloat(row[5]),
      closeTime: row[6],
    }));
  }

  private cachePath(symbol: string, interval: string): string {
    return path.join(this.cacheDir, symbol, `${interval}.json`);
  }

  private readCache(symbol: string, interval: string): Kline[] | null {
    try {
      const raw = fs.readFileSync(this.cachePath(symbol, interval), 'utf-8');
      return JSON.parse(raw);
    } catch {
      return null;
    }
  }

  private writeCache(symbol: string, interval: string, bars: Kline[]): void {
    try {
      const p = this.cachePath(symbol, interval);
      fs.mkdirSync(path.dirname(p), { recursive: true });
      fs.writeFileSync(p, JSON.stringify(bars));
    } catch (e) {
      this.logger.warn(`klines cache write failed for ${symbol}/${interval}: ${e}`);
    }
  }
}
