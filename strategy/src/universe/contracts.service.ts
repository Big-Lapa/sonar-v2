import { Injectable, Logger } from '@nestjs/common';
import axios from 'axios';

interface Cache<T> {
  fetchedAt: number;
  value: T;
}

const TTL_MS = 60 * 60 * 1000; // 1h — matches the venue contract-spec cache convention (trading's SymbolRulesCache)

/**
 * universe = Binance USDT-M perps ∩ contracts of every venue with a tradable account. Right
 * now only BingX is factored in (other venues' contract lists aren't fetched yet), so this
 * intersects Binance perps with BingX contracts specifically. No hardcoded symbol lists — both fetched live and cached.
 */
@Injectable()
export class ContractsService {
  private readonly logger = new Logger(ContractsService.name);
  private binanceCache: Cache<Set<string>> | null = null;
  private bingxCache: Cache<Set<string>> | null = null;

  async getUniverseSymbols(): Promise<string[]> {
    const [binance, bingx] = await Promise.all([this.getBinancePerps(), this.getBingxContracts()]);
    return [...binance].filter((s) => bingx.has(s)).sort();
  }

  private async getBinancePerps(): Promise<Set<string>> {
    if (this.binanceCache && Date.now() - this.binanceCache.fetchedAt < TTL_MS) {
      return this.binanceCache.value;
    }
    const resp = await axios.get('https://fapi.binance.com/fapi/v1/exchangeInfo', { timeout: 10_000 });
    const symbols = new Set<string>(
      (resp.data.symbols as any[])
        .filter((s) => s.status === 'TRADING' && s.contractType === 'PERPETUAL' && s.quoteAsset === 'USDT')
        .map((s) => s.symbol as string),
    );
    this.binanceCache = { fetchedAt: Date.now(), value: symbols };
    this.logger.log(`refreshed Binance perps: ${symbols.size} symbols`);
    return symbols;
  }

  private async getBingxContracts(): Promise<Set<string>> {
    if (this.bingxCache && Date.now() - this.bingxCache.fetchedAt < TTL_MS) {
      return this.bingxCache.value;
    }
    const resp = await axios.get('https://open-api.bingx.com/openApi/swap/v2/quote/contracts', { timeout: 10_000 });
    const symbols = new Set<string>(
      (resp.data.data as any[]).map((c) => (c.symbol as string).replace('-', '')),
    );
    this.bingxCache = { fetchedAt: Date.now(), value: symbols };
    this.logger.log(`refreshed BingX contracts: ${symbols.size} symbols`);
    return symbols;
  }
}
