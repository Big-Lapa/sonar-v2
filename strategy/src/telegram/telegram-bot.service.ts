import { Injectable, Logger, OnModuleInit } from '@nestjs/common';
import { Interval } from '@nestjs/schedule';
import { TelegramClientService, InlineButton } from './telegram-client.service';
import { isAllowedChat } from './access-guard';
import { formatExecutionReport, formatProposalMessage } from './format';
import { TradingApiService } from '../trading-client/trading-api.service';
import { SearchOrchestratorService } from '../search/search-orchestrator.service';

interface PendingPin {
  description: string;
  action: () => Promise<string>;
  expiresAt: number;
}

/**
 * 5: the whole Telegram state machine. Single poller (getUpdates), single bot — grabля #8.
 * Phase lives in trading.strategy_state (this service is stateless across restarts except for
 * in-memory-only concerns explicitly called out below).
 */
@Injectable()
export class TelegramBotService implements OnModuleInit {
  private readonly logger = new Logger(TelegramBotService.name);

  private offset = 0;
  private searching = false;
  /** In-memory only — a restart forgets a pending PIN confirmation; the operator just retries
   *  the command. Not worth persisting for a 60s confirmation window. */
  private readonly pendingPins = new Map<string, PendingPin>();
  /** Seeded to "now" on first tick so a restart never replays old notifications. */
  private notifAfterId: number | null = null;

  constructor(
    private readonly telegram: TelegramClientService,
    private readonly tradingApi: TradingApiService,
    private readonly search: SearchOrchestratorService,
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
    if (!isAllowedChat(chatId)) return;
    const text: string = (msg.text ?? '').trim();
    if (!text) return;

    const pending = this.pendingPins.get(String(chatId));
    if (pending) {
      this.pendingPins.delete(String(chatId));
      if (Date.now() > pending.expiresAt) {
        await this.telegram.sendMessage(chatId, 'PIN истёк, действие отменено');
        return;
      }
      if (text === (process.env.TELEGRAM_PIN ?? '')) {
        const result = await pending.action();
        await this.telegram.sendMessage(chatId, result);
      } else {
        await this.telegram.sendMessage(chatId, 'неверный PIN, отменено');
      }
      return;
    }

    const [cmd, ...args] = text.split(/\s+/);
    try {
      switch (cmd) {
        case '/search':
          await this.cmdSearch(chatId);
          break;
        case '/closeall':
          await this.cmdCloseAll(chatId, args[0]);
          break;
        case '/be':
          await this.cmdBreakeven(chatId, args[0]);
          break;
        case '/move':
          await this.cmdMove(chatId, args[0], args[1] as 'sl' | 'tp', args[2]);
          break;
        case '/ban':
          await this.cmdBan(chatId, args[0]);
          break;
        case '/unpause':
          await this.cmdUnpause(chatId, args[0]);
          break;
        default:
          break; // /status, /positions, /analyze — not implemented this stage (see summary)
      }
    } catch (e: any) {
      await this.telegram.sendMessage(chatId, `ошибка: ${e.message}`);
    }
  }

  private async cmdSearch(chatId: number): Promise<void> {
    if (this.searching) {
      await this.telegram.sendMessage(chatId, 'уже ищу…');
      return;
    }
    await this.runSearch(chatId);
  }

  private async cmdCloseAll(chatId: number, account?: string): Promise<void> {
    if (!account) {
      await this.telegram.sendMessage(chatId, 'использование: /closeall <account>');
      return;
    }
    this.requirePin(chatId, `close-all ${account}`, async () => {
      const r = await this.tradingApi.closeAll(account);
      return r.message;
    });
    await this.telegram.sendMessage(chatId, `подтверди PIN для close-all ${account}`);
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

  private async cmdMove(chatId: number, symbol?: string, type?: 'sl' | 'tp', price?: string): Promise<void> {
    if (!symbol || !type || !price) {
      await this.telegram.sendMessage(chatId, 'использование: /move <symbol> <sl|tp> <price>');
      return;
    }
    const positions = await this.tradingApi.getOpenPositions(undefined, symbol.toUpperCase());
    if (positions.length === 0) {
      await this.telegram.sendMessage(chatId, `нет открытой позиции по ${symbol}`);
      return;
    }
    const results = await Promise.all(positions.map((p) => this.tradingApi.moveStop(p.id, type, price)));
    await this.telegram.sendMessage(chatId, results.map((r) => r.message).join('\n'));
  }

  private async cmdBan(chatId: number, symbol?: string): Promise<void> {
    if (!symbol) {
      await this.telegram.sendMessage(chatId, 'использование: /ban <symbol>');
      return;
    }
    await this.tradingApi.banSymbol(symbol.toUpperCase(), 'заблокирован через /ban');
    await this.telegram.sendMessage(chatId, `${symbol.toUpperCase()} добавлен в чёрный список`);
  }

  private async cmdUnpause(chatId: number, account?: string): Promise<void> {
    if (!account) {
      await this.telegram.sendMessage(chatId, 'использование: /unpause <account>');
      return;
    }
    const r = await this.tradingApi.clearAutoPause(account);
    await this.telegram.sendMessage(chatId, r.wasPaused ? `${account}: пауза снята` : `${account}: паузы и не было`);
  }

  private requirePin(chatId: number, description: string, action: () => Promise<string>): void {
    this.pendingPins.set(String(chatId), { description, action, expiresAt: Date.now() + 60_000 });
  }

  // ---- Callback buttons ----

  private async handleCallback(cb: any): Promise<void> {
    const chatId = cb.message?.chat?.id;
    if (!isAllowedChat(chatId)) return;
    await this.telegram.answerCallbackQuery(cb.id);

    const [action, payload] = String(cb.data ?? '').split(':');
    try {
      if (action === 'prompt_accept') {
        await this.runSearch(chatId, cb.message.message_id);
      } else if (action === 'prompt_reject') {
        await this.tradingApi.setState('IDLE', null, null);
        await this.telegram.editMessageText(chatId, cb.message.message_id, 'поиск отклонён');
      } else if (action === 'proposal_open') {
        await this.confirmProposal(chatId, payload, cb.message.message_id);
      } else if (action === 'proposal_reject') {
        await this.tradingApi.rejectProposal(payload);
        await this.telegram.editMessageText(chatId, cb.message.message_id, '❌ отклонено');
      } else if (action === 'proposal_tv') {
        await this.showInTv(chatId, payload);
      }
    } catch (e: any) {
      // Safety net: a button tap must never die silently — the operator has no other signal
      // that anything happened at all (this exact failure mode caught live 2026-08-21).
      this.logger.error(`callback ${action} failed: ${e.message}`);
      await this.telegram.sendMessage(chatId, `ошибка обработки кнопки: ${e.message}`);
    }
  }

  /**
   * 7 (tv-bridge, Windows host process): draws the proposal's actual SL/TP on the chart and
   * sends a screenshot. "Мостик недоступен -> strategy пишет «TradingView недоступен» и работает
   * дальше — торговый контур от него не зависит никогда": any bridge failure degrades to that
   * one message, never touches the trading path.
   */
  private async showInTv(chatId: number, proposalId: string): Promise<void> {
    const bridgeUrl = process.env.TV_BRIDGE_URL;
    if (!bridgeUrl) {
      await this.telegram.sendMessage(chatId, 'TradingView недоступен (tv-bridge не настроен)');
      return;
    }
    try {
      const proposal = await this.tradingApi.getProposal(proposalId);
      const levels = [
        { price: parseFloat(proposal.slPrice), label: 'SL', kind: 'support' },
        { price: parseFloat(proposal.tpPrice), label: 'TP', kind: 'resistance' },
        { price: parseFloat(proposal.entryPrice), label: 'Entry', kind: 'hvn' },
      ];
      const drawResp = await fetch(`${bridgeUrl}/draw`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ symbol: proposal.symbol, levels }),
        signal: AbortSignal.timeout(15_000),
      });
      if (!drawResp.ok) throw new Error(`draw failed: ${drawResp.status} ${await drawResp.text()}`);

      const shotResp = await fetch(`${bridgeUrl}/screenshot`, { signal: AbortSignal.timeout(15_000) });
      if (!shotResp.ok) throw new Error(`screenshot failed: ${shotResp.status}`);
      const png = Buffer.from(await shotResp.arrayBuffer());

      await this.telegram.sendPhoto(chatId, png, `${proposal.symbol}: SL ${proposal.slPrice} · TP ${proposal.tpPrice}`);
    } catch (e: any) {
      this.logger.warn(`showInTv failed: ${e.message}`);
      await this.telegram.sendMessage(chatId, 'TradingView недоступен');
    }
  }

  private async confirmProposal(chatId: number, proposalId: string, messageId: number): Promise<void> {
    const proposal = await this.tradingApi.getProposal(proposalId);
    if (proposal.status !== 'PENDING') {
      await this.telegram.sendMessage(chatId, 'уже обработано');
      return;
    }
    const ttlMs = (await this.tradingApi.getSettings()).proposalTtlMs;
    if (Date.now() - proposal.createdAt > ttlMs) {
      await this.telegram.sendMessage(chatId, 'предложение устарело — детерминированный пересчёт без LLM (не реализован в этой сессии, отклони и запусти /search заново)');
      return;
    }
    let result;
    try {
      result = await this.tradingApi.confirmProposal(proposalId);
    } catch (e: any) {
      // trading answers ABORTED with HTTP 409 (price drift / exchange unreachable) — axios
      // throws on that by default, so the body has to be read off the error, not a normal
      // response. Bug caught live 2026-08-21: this used to be unhandled and the whole callback
      // died silently — the operator tapped Открыть and nothing happened, no message at all.
      const body = e?.response?.data;
      const reason = body?.abortReason ?? e.message;
      this.logger.warn(`confirmProposal ${proposalId} aborted: ${reason}`);
      await this.telegram.editMessageText(chatId, messageId, `⛔ отменено: ${reason}`);
      return;
    }
    await this.telegram.editMessageText(chatId, messageId, formatExecutionReport(result.accounts));
  }

  // ---- Search pipeline ----

  private async runSearch(chatId: number, editMessageId?: number): Promise<void> {
    this.searching = true;
    await this.tradingApi.setState('SEARCHING', null, null);
    const msg = editMessageId
      ? (await this.telegram.editMessageText(chatId, editMessageId, 'Ищу…'), editMessageId)
      : (await this.telegram.sendMessage(chatId, 'Ищу…')).message_id;

    try {
      const result = await this.search.run();
      if (result.proposals.length === 0) {
        await this.tradingApi.setState('IDLE', null, null);
        await this.telegram.editMessageText(
          chatId, msg,
          `кандидатов нет (вселенная ${result.universeSize}, от LLM ${result.candidatesFromLlm}, стоимость $${result.costUsd.toFixed(4)})`,
        );
        return;
      }
      await this.tradingApi.setState('PROPOSALS_PENDING', null, null);
      await this.telegram.editMessageText(chatId, msg, `найдено ${result.proposals.length} кандидат(ов), стоимость $${result.costUsd.toFixed(4)}`);

      for (const p of result.proposals) {
        const created = await this.tradingApi.createProposal(p.symbol, String(p.entry), String(p.sl), String(p.tp), p.rationale);
        const text = formatProposalMessage(p.symbol, String(p.entry), String(p.sl), String(p.tp), p.rr, p.rationale, created.accounts, result.costUsd);
        const buttons: InlineButton[][] = [
          [
            { text: 'Открыть', callback_data: `proposal_open:${created.proposalId}` },
            { text: 'Показать в TV', callback_data: `proposal_tv:${created.proposalId}` },
            { text: 'Отклонить', callback_data: `proposal_reject:${created.proposalId}` },
          ],
        ];
        await this.telegram.sendMessage(chatId, text, buttons);
      }
    } catch (e: any) {
      this.logger.error(`search failed: ${e.message}`);
      await this.telegram.editMessageText(chatId, msg, `ошибка поиска: ${e.message}`);
      await this.tradingApi.setState('IDLE', null, null);
    } finally {
      this.searching = false;
    }
  }

  // ---- Minute tick: cron-gated search prompt + notification relay (both piggyback the same tick,
  // trading's own poller stays the only *trading-side* periodic poll — 4.7) ----

  @Interval(60_000)
  private async minuteTick(): Promise<void> {
    await this.relayNotifications().catch((e) => this.logger.error(`notification relay failed: ${e.message}`));
    await this.checkPromptGate().catch((e) => this.logger.error(`prompt gate failed: ${e.message}`));
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
      if (ev.kind === 'POSITION_CLOSED') {
        await this.maybePrompt(chatIds[0], true);
      }
    }
  }

  private async checkPromptGate(): Promise<void> {
    const hours = (await this.tradingApi.getSettings()).searchCronHours;
    const nowHour = new Date().getUTCHours();
    const allowed = hours === '*' || hours.split(',').map((h) => parseInt(h.trim(), 10)).includes(nowHour);
    if (!allowed) return;
    const chatIds = (process.env.TELEGRAM_ALLOWED_CHAT_IDS ?? '').split(',').map((s) => s.trim()).filter(Boolean);
    if (chatIds.length > 0) await this.maybePrompt(chatIds[0], false);
  }

  private async maybePrompt(chatId: string, forceRefresh: boolean): Promise<void> {
    const state = await this.tradingApi.getState();
    if (state.phase !== 'IDLE' && state.phase !== 'PROMPT_PENDING') return;

    const slots = await this.tradingApi.getAccountSlots();
    const withRoom = slots.filter((s) => s.tradable && s.freeSlots > 0);
    if (withRoom.length === 0) return;

    const text = `Свободно мест: ${withRoom.map((s) => `${s.accountId} ${s.freeSlots}`).join(', ')}. Начать поиск?`;
    const buttons: InlineButton[][] = [[{ text: 'Принять', callback_data: 'prompt_accept' }, { text: 'Отклонить', callback_data: 'prompt_reject' }]];

    if (state.phase === 'PROMPT_PENDING' && state.promptMessageId) {
      await this.telegram.editMessageText(chatId, state.promptMessageId, text, buttons);
      return;
    }
    const sent = await this.telegram.sendMessage(chatId, text, buttons);
    await this.tradingApi.setState('PROMPT_PENDING', sent.message_id, Date.now());
  }
}
