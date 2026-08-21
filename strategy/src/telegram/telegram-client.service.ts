import { Injectable, Logger } from '@nestjs/common';
import axios, { AxiosInstance } from 'axios';

export interface InlineButton {
  text: string;
  callback_data: string;
}

/** Thin Telegram Bot API wrapper. No SDK — the surface we need is tiny. */
@Injectable()
export class TelegramClientService {
  private readonly logger = new Logger(TelegramClientService.name);
  private readonly http: AxiosInstance;

  constructor() {
    const token = process.env.TELEGRAM_BOT_TOKEN ?? '';
    this.http = axios.create({ baseURL: `https://api.telegram.org/bot${token}`, timeout: 35_000 });
  }

  async getUpdates(offset: number, timeoutSec = 30): Promise<any[]> {
    const resp = await this.http.get('/getUpdates', {
      params: { offset, timeout: timeoutSec, allowed_updates: JSON.stringify(['message', 'callback_query']) },
    });
    return resp.data.result ?? [];
  }

  async sendMessage(chatId: number | string, text: string, buttons?: InlineButton[][]): Promise<{ message_id: number }> {
    const resp = await this.http.post('/sendMessage', {
      chat_id: chatId,
      text,
      reply_markup: buttons ? { inline_keyboard: buttons } : undefined,
    });
    return resp.data.result;
  }

  async editMessageText(chatId: number | string, messageId: number, text: string, buttons?: InlineButton[][]): Promise<void> {
    try {
      await this.http.post('/editMessageText', {
        chat_id: chatId,
        message_id: messageId,
        text,
        reply_markup: buttons ? { inline_keyboard: buttons } : undefined,
      });
    } catch (e: any) {
      // "message is not modified" is a benign race (two ticks editing the same counter) — ignore.
      if (!String(e?.response?.data?.description ?? '').includes('not modified')) {
        this.logger.warn(`editMessageText failed: ${e.message}`);
      }
    }
  }

  async answerCallbackQuery(callbackQueryId: string, text?: string): Promise<void> {
    try {
      await this.http.post('/answerCallbackQuery', { callback_query_id: callbackQueryId, text });
    } catch (e: any) {
      this.logger.warn(`answerCallbackQuery failed: ${e.message}`);
    }
  }
}
