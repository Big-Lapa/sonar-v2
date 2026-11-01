import { Injectable, Logger, OnModuleInit } from '@nestjs/common';
import { Interval } from '@nestjs/schedule';
import { TelegramClientService } from './telegram-client.service';
import { isAllowedSender, pinConfigured, pinMatches, TICKER_RE } from './access-guard';
import { TradingApiService } from '../trading-client/trading-api.service';
import { DEFAULT_LANG, isLang, Lang, langFromTelegram, Params, t, tMsg } from '../i18n';

interface PendingPin {
  description: string;
  action: (lang: Lang) => Promise<string>;
  expiresAt: number;
}

/** Wrong PINs in a row before PIN-gated commands lock for this sender. */
const MAX_PIN_FAILURES = 3;
const PIN_LOCKOUT_MS = 15 * 60_000;
/** A wrong PIN older than this no longer counts toward the lockout. */
const PIN_FAILURE_WINDOW_MS = 15 * 60_000;

/** Lockout key for a pinKey (`chatId:userId`): the user id alone, falling back to the whole key. */
function lockKey(pinKey: string): string {
  return pinKey.slice(pinKey.indexOf(':') + 1) || pinKey;
}

/**
 * Manual ops only — the LLM candidate-search pipeline (/search, the cron/close-triggered
 * "start search?" prompt, proposal open/reject buttons) has been retired; this bot is now a thin
 * remote control for balances/positions/stop moves, plus a relay for trading's own notifications.
 * Single poller (getUpdates), single bot — pitfall #8.
 */
@Injectable()
export class TelegramBotService implements OnModuleInit {
  private readonly logger = new Logger(TelegramBotService.name);

  private offset = 0;
  /** In-memory only — a restart forgets a pending PIN confirmation; the operator just retries
   *  the command. Not worth persisting for a 60s confirmation window. */
  private readonly pendingPins = new Map<string, PendingPin>();
  /** Wrong PINs per SENDER (user id, see lockKey) — not per chat, so a user allowed in several
   *  chats doesn't get MAX_PIN_FAILURES attempts in each. In-memory like pendingPins: a restart
   *  resets it, which is why .env.example asks for a PIN longer than 4 digits. */
  private readonly pinFailures = new Map<string, { count: number; lockedUntil: number; lastFailureAt: number }>();
  /** Interface language chosen with /lang, per chat. In-memory like the PIN state — after a restart a
   *  chat falls back to its Telegram client language. */
  private readonly chatLangs = new Map<string, Lang>();
  /** Last language detected from a chat's sender, so notifications (no sender) follow it too. */
  private readonly detectedLangs = new Map<string, Lang>();
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
    const lang = this.langOf(chatId, msg.from?.language_code);
    // Remember only a code Telegram actually sent: a message without one must not reset the chat to English.
    if (msg.from?.language_code) this.detectedLangs.set(String(chatId), langFromTelegram(msg.from.language_code));

    // Keyed by chat AND sender, so in a group only the person who asked can answer the PIN prompt.
    const pinKey = `${chatId}:${msg.from?.id ?? ''}`;
    const pending = this.pendingPins.get(pinKey);
    if (pending && text.startsWith('/')) {
      // A command instead of a PIN: the operator moved on. Cancel the confirmation without
      // counting it as a wrong PIN (that would burn lockout attempts), then run the command.
      this.pendingPins.delete(pinKey);
      if (Date.now() <= pending.expiresAt) {
        await this.telegram.sendMessage(chatId, t(lang, 'pin.cancelled', { description: pending.description }));
      }
    } else if (pending) {
      this.pendingPins.delete(pinKey);
      // Don't leave the PIN in the chat history (in a group every member would see it).
      await this.telegram.deleteMessage(chatId, msg.message_id);
      if (Date.now() > pending.expiresAt) {
        await this.telegram.sendMessage(chatId, t(lang, 'pin.expired'));
        return;
      }
      // Lockout is per user, but prompts are per chat: a user allowed in several chats could open a
      // prompt in each before the lockout and keep answering them after it. Re-check here, before
      // the PIN is even compared.
      const lockedMin = this.lockedMinutes(lockKey(pinKey));
      if (lockedMin > 0) {
        await this.telegram.sendMessage(chatId, t(lang, 'pin.locked', { minutes: lockedMin }));
        return;
      }
      if (pinMatches(text)) {
        this.pinFailures.delete(lockKey(pinKey));
        // Outside the command try/catch below — without this a failing action (4xx from trading,
        // position already gone) would only reach the log and the operator would get silence.
        try {
          await this.telegram.sendMessage(chatId, await pending.action(lang));
        } catch (e: any) {
          await this.telegram.sendMessage(chatId, t(lang, 'error', { message: e.message }));
        }
      } else {
        const left = this.registerPinFailure(lockKey(pinKey));
        await this.telegram.sendMessage(chatId, left > 0
          ? t(lang, 'pin.wrong', { left })
          : t(lang, 'pin.wrong_locked', { minutes: PIN_LOCKOUT_MS / 60_000 }));
      }
      return;
    }

    const [cmd, ...args] = text.split(/\s+/);
    try {
      switch (cmd) {
        case '/closeall':
          await this.cmdCloseAll(chatId, lang, pinKey, args[0]);
          break;
        case '/be':
          await this.cmdBreakeven(chatId, lang, args[0]);
          break;
        case '/move':
          await this.cmdMove(chatId, lang, pinKey, args[0], args[1] as 'sl' | 'tp', args[2]);
          break;
        case '/ban':
          await this.cmdBan(chatId, lang, args[0]);
          break;
        case '/unpause':
          await this.cmdUnpause(chatId, lang, pinKey, args[0]);
          break;
        case '/help':
        case '/start':
          await this.cmdHelp(chatId, lang);
          break;
        case '/lang':
          await this.cmdLang(chatId, msg.from?.language_code, args[0]);
          break;
        case '/status':
          await this.cmdStatus(chatId, lang);
          break;
        case '/positions':
          await this.cmdPositions(chatId, lang, args[0]);
          break;
        default:
          // Same failure mode as the confirm-proposal bug (2026-08-21): an unhandled command
          // must never just go quiet — the operator has no other way to tell "not implemented"
          // apart from "nothing happened, is the bot even alive".
          // Echo only what looks like a command: plain text here is often a PIN sent after its
          // prompt was cancelled or expired, and repeating it would put the PIN in the chat again.
          await this.telegram.sendMessage(chatId, cmd.startsWith('/')
            ? t(lang, 'cmd.unknown_named', { cmd })
            : t(lang, 'cmd.unknown'));
      }
    } catch (e: any) {
      await this.telegram.sendMessage(chatId, t(lang, 'error', { message: e.message }));
    }
  }

  /** Language of a chat: its /lang choice, else the sender's Telegram client language (the last one seen when there is no sender, e.g. notifications). */
  private langOf(chatId: number | string, telegramCode?: string): Lang {
    const key = String(chatId);
    const detected = telegramCode !== undefined ? langFromTelegram(telegramCode) : this.detectedLangs.get(key);
    return this.chatLangs.get(key) ?? detected ?? DEFAULT_LANG;
  }

  /** A backend result: translated by its code when it has one, else its plain message. */
  private opText(lang: Lang, r: { message: string; code?: string | null; params?: Params | null }): string {
    return tMsg(lang, r.code, r.params, r.message);
  }

  private async cmdLang(chatId: number, telegramCode: string | undefined, arg?: string): Promise<void> {
    const current = this.langOf(chatId, telegramCode);
    const wanted = arg?.toLowerCase();
    if (!isLang(wanted)) {
      await this.telegram.sendMessage(chatId, t(current, 'lang.usage', { current }));
      return;
    }
    this.chatLangs.set(String(chatId), wanted);
    await this.telegram.sendMessage(chatId, t(wanted, 'lang.changed'));
  }

  private async cmdHelp(chatId: number, lang: Lang): Promise<void> {
    await this.telegram.sendMessage(chatId, t(lang, 'help.text'));
  }

  private async cmdStatus(chatId: number, lang: Lang): Promise<void> {
    const accounts = await this.tradingApi.getAccounts();
    const lines = await Promise.all(
      accounts.map(async (a) => {
        const b = await this.tradingApi.getBalance(a.id);
        if (b.error) return `${a.id}: ${b.error}`;
        return t(lang, 'fmt.status', { account: a.id, equity: b.equity ?? b.balance, asset: b.asset ?? '', pnl: b.unrealizedPnl ?? '—' });
      }),
    );
    await this.telegram.sendMessage(chatId, lines.join('\n'));
  }

  private async cmdPositions(chatId: number, lang: Lang, account?: string): Promise<void> {
    if (!account) {
      await this.telegram.sendMessage(chatId, t(lang, 'usage.positions'));
      return;
    }
    const result = await this.tradingApi.getLivePositions(account);
    if (result.error) {
      await this.telegram.sendMessage(chatId, `${account}: ${result.error}`);
      return;
    }
    if (result.positions.length === 0) {
      await this.telegram.sendMessage(chatId, t(lang, 'positions.none', { account }));
      return;
    }
    const lines = result.positions.map(
      (p) => t(lang, 'fmt.live_position', { symbol: p.symbol, side: p.side, qty: p.qty, price: p.avgPrice, mark: p.markPrice, pnl: p.unrealizedPnl }),
    );
    await this.telegram.sendMessage(chatId, `${account}:\n${lines.join('\n')}`);
  }

  private async cmdCloseAll(chatId: number, lang: Lang, pinKey: string, account?: string): Promise<void> {
    if (!account) {
      await this.telegram.sendMessage(chatId, t(lang, 'usage.closeall'));
      return;
    }
    await this.requirePin(chatId, lang, pinKey, `close-all ${account}`, async (actionLang) => {
      const r = await this.tradingApi.closeAll(account);
      return this.opText(actionLang, r);
    });
  }

  private async cmdBreakeven(chatId: number, lang: Lang, symbol?: string): Promise<void> {
    if (!symbol) {
      await this.telegram.sendMessage(chatId, t(lang, 'usage.be'));
      return;
    }
    const positions = await this.tradingApi.getOpenPositions(undefined, symbol.toUpperCase());
    if (positions.length === 0) {
      await this.telegram.sendMessage(chatId, t(lang, 'position.none', { symbol }));
      return;
    }
    const results = await Promise.all(positions.map((p) => this.tradingApi.breakeven(p.id)));
    await this.telegram.sendMessage(chatId, results.map((r) => this.opText(lang, r)).join('\n'));
  }

  private async cmdMove(chatId: number, lang: Lang, pinKey: string, symbol?: string, type?: 'sl' | 'tp', price?: string): Promise<void> {
    if (!symbol || (type !== 'sl' && type !== 'tp') || !price) {
      await this.telegram.sendMessage(chatId, t(lang, 'usage.move'));
      return;
    }
    const sym = symbol.toUpperCase();
    // Early check so a typo fails before the PIN prompt, not after it.
    if ((await this.tradingApi.getOpenPositions(undefined, sym)).length === 0) {
      await this.telegram.sendMessage(chatId, t(lang, 'position.none', { symbol }));
      return;
    }
    // PIN-gated: moving SL is enough to strip a position's protection, same stakes as close-all.
    await this.requirePin(chatId, lang, pinKey, `${type.toUpperCase()} ${sym} → ${price}`, async (actionLang) => {
      // Re-read after the PIN: up to 60s may have passed, positions may have closed or opened.
      const positions = await this.tradingApi.getOpenPositions(undefined, sym);
      if (positions.length === 0) return t(actionLang, 'position.none', { symbol: sym });
      const results = await Promise.all(positions.map((p) => this.tradingApi.moveStop(p.id, type, price)));
      return results.map((r) => this.opText(actionLang, r)).join('\n');
    });
  }

  private async cmdBan(chatId: number, lang: Lang, symbol?: string): Promise<void> {
    if (!symbol) {
      await this.telegram.sendMessage(chatId, t(lang, 'usage.ban'));
      return;
    }
    // Same alphabet trading enforces (Symbols.java) — say so here instead of relaying a bare 400.
    if (!TICKER_RE.test(symbol.toUpperCase())) {
      await this.telegram.sendMessage(chatId, t(lang, 'ban.invalid', { symbol }));
      return;
    }
    await this.tradingApi.banSymbol(symbol.toUpperCase(), 'banned via /ban');
    await this.telegram.sendMessage(chatId, t(lang, 'ban.done', { symbol: symbol.toUpperCase() }));
  }

  private async cmdUnpause(chatId: number, lang: Lang, pinKey: string, account?: string): Promise<void> {
    if (!account) {
      await this.telegram.sendMessage(chatId, t(lang, 'usage.unpause'));
      return;
    }
    // PIN-gated: auto-pause is the risk engine's own brake; lifting it shouldn't take one message.
    await this.requirePin(chatId, lang, pinKey, `unpause ${account}`, async (actionLang) => {
      const r = await this.tradingApi.clearAutoPause(account);
      return t(actionLang, r.wasPaused ? 'unpause.done' : 'unpause.none', { account });
    });
  }

  private async requirePin(chatId: number, lang: Lang, pinKey: string, description: string, action: (lang: Lang) => Promise<string>): Promise<void> {
    if (!pinConfigured()) {
      await this.telegram.sendMessage(chatId, t(lang, 'pin.not_set'));
      return;
    }
    const lockedMin = this.lockedMinutes(lockKey(pinKey));
    if (lockedMin > 0) {
      await this.telegram.sendMessage(chatId, t(lang, 'pin.locked', { minutes: lockedMin }));
      return;
    }
    this.pendingPins.set(pinKey, { description, action, expiresAt: Date.now() + 60_000 });
    await this.telegram.sendMessage(chatId, t(lang, 'pin.confirm', { description }));
  }

  /** Minutes left on this sender's PIN lockout, 0 when not locked. */
  private lockedMinutes(key: string): number {
    const lock = this.pinFailures.get(key);
    if (!lock || lock.lockedUntil <= Date.now()) return 0;
    return Math.ceil((lock.lockedUntil - Date.now()) / 60_000);
  }

  /** Records a wrong PIN; returns attempts left before lockout (0 = locked now). */
  private registerPinFailure(key: string): number {
    const now = Date.now();
    const f = this.pinFailures.get(key) ?? { count: 0, lockedUntil: 0, lastFailureAt: 0 };
    // Fresh count after an expired lockout, or when the previous miss is old enough — two typos
    // a week ago shouldn't make today's first one a lockout.
    if ((f.lockedUntil !== 0 && f.lockedUntil <= now) || now - f.lastFailureAt > PIN_FAILURE_WINDOW_MS) {
      f.count = 0;
      f.lockedUntil = 0;
    }
    f.count++;
    f.lastFailureAt = now;
    if (f.count >= MAX_PIN_FAILURES) f.lockedUntil = now + PIN_LOCKOUT_MS;
    this.pinFailures.set(key, f);
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
        // Rendered per chat, in the language chosen there. Rows written before message codes existed
        // (or a code this bot doesn't know) show the stored English message.
        const lang = this.langOf(chatId);
        const body = tMsg(lang, ev.code, ev.params, ev.message);
        await this.telegram.sendMessage(chatId, `[${ev.kind}] ${ev.accountId ?? ''} ${ev.symbol ?? ''}: ${body}`);
      }
    }
  }
}
