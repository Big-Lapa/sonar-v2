import { Injectable, Logger, OnModuleInit } from '@nestjs/common';
import { Interval } from '@nestjs/schedule';
import { TelegramClientService } from './telegram-client.service';
import { isAllowedSender, pinConfigured, pinMatches } from './access-guard';
import { TradingApiService } from '../trading-client/trading-api.service';

interface PendingPin {
  description: string;
  action: () => Promise<string>;
  expiresAt: number;
}

/** Wrong PINs in a row before PIN-gated commands lock for this sender. */
const MAX_PIN_FAILURES = 3;
const PIN_LOCKOUT_MS = 15 * 60_000;

/**
 * Manual ops only — the LLM candidate-search pipeline (/search, the cron/close-triggered
 * "начать поиск?" prompt, proposal open/reject buttons) has been retired; this bot is now a thin
 * remote control for balances/positions/stop moves, plus a relay for trading's own notifications.
 * Single poller (getUpdates), single bot — grabля #8.
 */
@Injectable()
export class TelegramBotService implements OnModuleInit {
  private readonly logger = new Logger(TelegramBotService.name);

  private offset = 0;
  /** In-memory only — a restart forgets a pending PIN confirmation; the operator just retries
   *  the command. Not worth persisting for a 60s confirmation window. */
  private readonly pendingPins = new Map<string, PendingPin>();
  /** Consecutive wrong PINs per sender (same key as pendingPins); in-memory like pendingPins. */
  private readonly pinFailures = new Map<string, { count: number; lockedUntil: number }>();
  /** Seeded to "now" on first tick so a restart never replays old notifications. */
  private notifAfterId: number | null = null;

  constructor(
    private readonly telegram: TelegramClientService,
    private readonly tradingApi: TradingApiService,
  ) {}

  onModuleInit(): void {
    if (!process.env.TELEGRAM_BOT_TOKEN) {
      this.logger.warn('TELEGRAM_BOT_TOKEN not set — Telegram bot disabled');
      return;
    }
    void this.pollLoop();
  }

  private async pollLoop(): Promise<void> {
    for (;;) {
      try {
        const updates = await this.telegram.getUpdates(this.offset + 1);
        for (const u of updates) {
          this.offset = Math.max(this.offset, u.update_id);
          await this.handleUpdate(u).catch((e) => this.logger.error(`update handling failed: ${e.message}`));
        }
      } catch (e: any) {
        this.logger.error(`getUpdates failed: ${e.message}`);
        await new Promise((r) => setTimeout(r, 5000));
      }
    }
  }

  private async handleUpdate(u: any): Promise<void> {
    if (u.message) await this.handleMessage(u.message);
    else if (u.callback_query) await this.handleCallback(u.callback_query);
  }

  // ---- Messages / commands ----

  private async handleMessage(msg: any): Promise<void> {
    const chatId = msg.chat.id;
    if (!isAllowedSender(msg.chat, msg.from)) return;
    const text: string = (msg.text ?? '').trim();
    if (!text) return;

    // Keyed by chat AND sender, so in a group only the person who asked can answer the PIN prompt.
    const pinKey = `${chatId}:${msg.from?.id ?? ''}`;
    const pending = this.pendingPins.get(pinKey);
    if (pending) {
      this.pendingPins.delete(pinKey);
      if (Date.now() > pending.expiresAt) {
        await this.telegram.sendMessage(chatId, 'PIN истёк, действие отменено');
        return;
      }
      if (pinMatches(text)) {
        this.pinFailures.delete(pinKey);
        const result = await pending.action();
        await this.telegram.sendMessage(chatId, result);
      } else {
        const left = this.registerPinFailure(pinKey);
        await this.telegram.sendMessage(chatId, left > 0
          ? `неверный PIN, отменено (осталось попыток: ${left})`
          : `неверный PIN, отменено — команды с PIN заблокированы на ${PIN_LOCKOUT_MS / 60_000} мин`);
      }
      return;
    }

    const [cmd, ...args] = text.split(/\s+/);
    try {
      switch (cmd) {
        case '/closeall':
          await this.cmdCloseAll(chatId, pinKey, args[0]);
          break;
        case '/be':
          await this.cmdBreakeven(chatId, args[0]);
          break;
        case '/move':
          await this.cmdMove(chatId, pinKey, args[0], args[1] as 'sl' | 'tp', args[2]);
          break;
        case '/ban':
          await this.cmdBan(chatId, args[0]);
          break;
        case '/unpause':
          await this.cmdUnpause(chatId, pinKey, args[0]);
          break;
        case '/help':
        case '/start':
          await this.cmdHelp(chatId);
          break;
        case '/status':
          await this.cmdStatus(chatId);
          break;
        case '/positions':
          await this.cmdPositions(chatId, args[0]);
          break;
        default:
          // Same failure mode as the confirm-proposal bug (2026-08-21): an unhandled command
          // must never just go quiet — the operator has no other way to tell "not implemented"
          // apart from "nothing happened, is the bot even alive".
          await this.telegram.sendMessage(chatId, `неизвестная команда ${cmd}. /help — список команд`);
      }
    } catch (e: any) {
      await this.telegram.sendMessage(chatId, `ошибка: ${e.message}`);
    }
  }

  private async cmdHelp(chatId: number): Promise<void> {
    const lines = [
      'Команды (ручной режим — открытие позиций через REST /api/v1/strategy/proposals с accountId):',
      '/closeall <account> — закрыть все позиции аккаунта (нужен PIN)',
      '/be <symbol> — стоп в безубыток',
      '/move <symbol> sl|tp <price> — переставить SL или TP (нужен PIN)',
      '/ban <symbol> — добавить символ в чёрный список',
      '/unpause <account> — снять auto-pause (нужен PIN)',
      '/status — баланс по каждому аккаунту',
      '/positions <account> — открытые позиции с PnL в моменте',
    ];
    await this.telegram.sendMessage(chatId, lines.join('\n'));
  }

  private async cmdStatus(chatId: number): Promise<void> {
    const accounts = await this.tradingApi.getAccounts();
    const lines = await Promise.all(
      accounts.map(async (a) => {
        const b = await this.tradingApi.getBalance(a.id);
        if (b.error) return `${a.id}: ${b.error}`;
        return `${a.id}: equity ${b.equity ?? b.balance} ${b.asset ?? ''} (unrealPnL ${b.unrealizedPnl ?? '—'})`;
      }),
    );
    await this.telegram.sendMessage(chatId, lines.join('\n'));
  }

  private async cmdPositions(chatId: number, account?: string): Promise<void> {
    if (!account) {
      await this.telegram.sendMessage(chatId, 'использование: /positions <account>');
      return;
    }
    const result = await this.tradingApi.getLivePositions(account);
    if (result.error) {
      await this.telegram.sendMessage(chatId, `${account}: ${result.error}`);
      return;
    }
    if (result.positions.length === 0) {
      await this.telegram.sendMessage(chatId, `${account}: нет открытых позиций`);
      return;
    }
    const lines = result.positions.map(
      (p) => `${p.symbol} ${p.side} ${p.qty} @ ${p.avgPrice} · mark ${p.markPrice} · PnL ${p.unrealizedPnl}`,
    );
    await this.telegram.sendMessage(chatId, `${account}:\n${lines.join('\n')}`);
  }

  private async cmdCloseAll(chatId: number, pinKey: string, account?: string): Promise<void> {
    if (!account) {
      await this.telegram.sendMessage(chatId, 'использование: /closeall <account>');
      return;
    }
    await this.requirePin(chatId, pinKey, `close-all ${account}`, async () => {
      const r = await this.tradingApi.closeAll(account);
      return r.message;
    });
  }

  private async cmdBreakeven(chatId: number, symbol?: string): Promise<void> {
    if (!symbol) {
      await this.telegram.sendMessage(chatId, 'использование: /be <symbol>');
      return;
    }
    const positions = await this.tradingApi.getOpenPositions(undefined, symbol.toUpperCase());
    if (positions.length === 0) {
      await this.telegram.sendMessage(chatId, `нет открытой позиции по ${symbol}`);
      return;
    }
    const results = await Promise.all(positions.map((p) => this.tradingApi.breakeven(p.id)));
    await this.telegram.sendMessage(chatId, results.map((r) => r.message).join('\n'));
  }

  private async cmdMove(chatId: number, pinKey: string, symbol?: string, type?: 'sl' | 'tp', price?: string): Promise<void> {
    if (!symbol || (type !== 'sl' && type !== 'tp') || !price) {
      await this.telegram.sendMessage(chatId, 'использование: /move <symbol> <sl|tp> <price>');
      return;
    }
    const positions = await this.tradingApi.getOpenPositions(undefined, symbol.toUpperCase());
    if (positions.length === 0) {
      await this.telegram.sendMessage(chatId, `нет открытой позиции по ${symbol}`);
      return;
    }
    // PIN-gated: moving SL is enough to strip a position's protection, same stakes as close-all.
    await this.requirePin(chatId, pinKey, `${type.toUpperCase()} ${symbol.toUpperCase()} → ${price}`, async () => {
      const results = await Promise.all(positions.map((p) => this.tradingApi.moveStop(p.id, type, price)));
      return results.map((r) => r.message).join('\n');
    });
  }

  private async cmdBan(chatId: number, symbol?: string): Promise<void> {
    if (!symbol) {
      await this.telegram.sendMessage(chatId, 'использование: /ban <symbol>');
      return;
    }
    // Same alphabet trading enforces (Symbols.java) — say so here instead of relaying a bare 400.
    if (!/^[A-Z0-9][A-Z0-9_-]{1,39}$/.test(symbol.toUpperCase())) {
      await this.telegram.sendMessage(chatId, `некорректный тикер: ${symbol}`);
      return;
    }
    await this.tradingApi.banSymbol(symbol.toUpperCase(), 'заблокирован через /ban');
    await this.telegram.sendMessage(chatId, `${symbol.toUpperCase()} добавлен в чёрный список`);
  }

  private async cmdUnpause(chatId: number, pinKey: string, account?: string): Promise<void> {
    if (!account) {
      await this.telegram.sendMessage(chatId, 'использование: /unpause <account>');
      return;
    }
    // PIN-gated: auto-pause is the risk engine's own brake; lifting it shouldn't take one message.
    await this.requirePin(chatId, pinKey, `unpause ${account}`, async () => {
      const r = await this.tradingApi.clearAutoPause(account);
      return r.wasPaused ? `${account}: пауза снята` : `${account}: паузы и не было`;
    });
  }

  private async requirePin(chatId: number, pinKey: string, description: string, action: () => Promise<string>): Promise<void> {
    if (!pinConfigured()) {
      await this.telegram.sendMessage(chatId, 'TELEGRAM_PIN не задан — команды с PIN отключены');
      return;
    }
    const lock = this.pinFailures.get(pinKey);
    if (lock && lock.lockedUntil > Date.now()) {
      const min = Math.ceil((lock.lockedUntil - Date.now()) / 60_000);
      await this.telegram.sendMessage(chatId, `слишком много неверных PIN — команды с PIN заблокированы ещё ${min} мин`);
      return;
    }
    this.pendingPins.set(pinKey, { description, action, expiresAt: Date.now() + 60_000 });
    await this.telegram.sendMessage(chatId, `подтверди PIN для ${description}`);
  }

  /** Records a wrong PIN; returns attempts left before lockout (0 = locked now). */
  private registerPinFailure(pinKey: string): number {
    const now = Date.now();
    const f = this.pinFailures.get(pinKey) ?? { count: 0, lockedUntil: 0 };
    if (f.lockedUntil !== 0 && f.lockedUntil <= now) {
      f.count = 0;
      f.lockedUntil = 0;
    }
    f.count++;
    if (f.count >= MAX_PIN_FAILURES) f.lockedUntil = now + PIN_LOCKOUT_MS;
    this.pinFailures.set(pinKey, f);
    return Math.max(0, MAX_PIN_FAILURES - f.count);
  }

  // ---- Callback buttons ----
  // Nothing sends inline-keyboard messages anymore (the search/proposal pipeline that used to is
  // retired) — kept minimal in case an old message with buttons is still tapped.

  private async handleCallback(cb: any): Promise<void> {
    if (!isAllowedSender(cb.message?.chat, cb.from)) return;
    await this.telegram.answerCallbackQuery(cb.id);
  }

  // ---- Minute tick: notification relay only (trading's own poller stays the only *trading-side*
  // periodic poll — 4.7; the LLM search-prompt gate that used to piggyback this tick is retired
  // along with the rest of the search pipeline) ----

  @Interval(60_000)
  private async minuteTick(): Promise<void> {
    await this.relayNotifications().catch((e) => this.logger.error(`notification relay failed: ${e.message}`));
  }

  private async relayNotifications(): Promise<void> {
    if (this.notifAfterId === null) {
      const seed = await this.tradingApi.getNotifications(0);
      this.notifAfterId = seed.length > 0 ? seed[seed.length - 1].id : 0;
      return;
    }
    const events = await this.tradingApi.getNotifications(this.notifAfterId);
    if (events.length === 0) return;
    this.notifAfterId = events[events.length - 1].id;

    const chatIds = (process.env.TELEGRAM_ALLOWED_CHAT_IDS ?? '').split(',').map((s) => s.trim()).filter(Boolean);
    for (const ev of events) {
      for (const chatId of chatIds) {
        await this.telegram.sendMessage(chatId, `[${ev.kind}] ${ev.accountId ?? ''} ${ev.symbol ?? ''}: ${ev.message}`);
      }
    }
  }
}
