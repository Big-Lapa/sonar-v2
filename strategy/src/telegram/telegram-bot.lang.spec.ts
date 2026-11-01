import { TelegramBotService } from './telegram-bot.service';
import { TelegramClientService } from './telegram-client.service';
import { TradingApiService } from '../trading-client/trading-api.service';
import { t } from '../i18n';

const CHAT = 42;
const OTHER_CHAT = 43;
const CYRILLIC = /[\u0400-\u04FF]/;

describe('TelegramBotService interface language', () => {
  const env = { ...process.env };
  let sent: { chat: number | string; text: string }[];
  let nextMessageId: number;
  let api: Record<string, jest.Mock>;
  let bot: TelegramBotService;

  const say = (text: string, opts: { chat?: number; languageCode?: string } = {}) => {
    const chat = opts.chat ?? CHAT;
    return (bot as any).handleMessage({
      chat: { id: chat, type: 'private' },
      from: { id: chat, language_code: opts.languageCode },
      text,
      message_id: ++nextMessageId,
    });
  };
  const last = () => sent[sent.length - 1].text;

  beforeEach(() => {
    process.env.TELEGRAM_ALLOWED_CHAT_IDS = `${CHAT},${OTHER_CHAT}`;
    process.env.TELEGRAM_PIN = '1234';
    sent = [];
    nextMessageId = 0;
    api = {
      getOpenPositions: jest.fn(async () => []),
      closeAll: jest.fn(async () => ({ message: 'fallback text', code: 'CLOSE_ALL_SENT', params: { count: '2' } })),
      getNotifications: jest.fn(async () => []),
    };
    const telegram = {
      sendMessage: jest.fn(async (chat: number | string, text: string) => void sent.push({ chat, text })),
      deleteMessage: jest.fn(async () => undefined),
    };
    bot = new TelegramBotService(telegram as unknown as TelegramClientService, api as unknown as TradingApiService);
  });

  afterEach(() => {
    jest.restoreAllMocks();
    process.env = { ...env };
  });

  it('defaults to English when Telegram sends no language_code', async () => {
    await say('/foo');
    expect(last()).toBe(t('en', 'cmd.unknown_named', { cmd: '/foo' }));
    expect(last()).not.toMatch(CYRILLIC);
  });

  it('defaults to Russian for a Russian Telegram client', async () => {
    await say('/foo', { languageCode: 'ru' });
    expect(last()).toBe(t('ru', 'cmd.unknown_named', { cmd: '/foo' }));
    expect(last()).toMatch(CYRILLIC);
  });

  it('/lang ru switches the interface and confirms in the new language', async () => {
    await say('/lang ru');
    expect(last()).toBe(t('ru', 'lang.changed'));
    await say('/help');
    expect(last()).toBe(t('ru', 'help.text'));
    expect(last()).toMatch(CYRILLIC);
  });

  it('/lang en switches back, even for a Russian Telegram client', async () => {
    await say('/lang en', { languageCode: 'ru' });
    expect(last()).toBe(t('en', 'lang.changed'));
    await say('/foo', { languageCode: 'ru' });
    expect(last()).toBe(t('en', 'cmd.unknown_named', { cmd: '/foo' }));
  });

  it('/lang without an argument shows the current language and usage', async () => {
    await say('/lang');
    expect(last()).toBe(t('en', 'lang.usage', { current: 'en' }));
    await say('/lang ru');
    await say('/lang');
    expect(last()).toBe(t('ru', 'lang.usage', { current: 'ru' }));
  });

  it('/lang with an unsupported language keeps the current one', async () => {
    await say('/lang de');
    expect(last()).toBe(t('en', 'lang.usage', { current: 'en' }));
    await say('/foo');
    expect(last()).toBe(t('en', 'cmd.unknown_named', { cmd: '/foo' }));
  });

  it('keeps the choice per chat', async () => {
    await say('/lang ru');
    await say('/foo', { chat: OTHER_CHAT });
    expect(last()).toBe(t('en', 'cmd.unknown_named', { cmd: '/foo' }));
  });

  it('/help lists /lang', async () => {
    await say('/help');
    expect(last()).toContain('/lang');
  });

  it('PIN prompts follow the chosen language', async () => {
    await say('/lang ru');
    await say('/closeall acc1');
    expect(last()).toBe(t('ru', 'pin.confirm', { description: 'close-all acc1' }));
  });

  it('translates a backend result by its code, falling back to its message without one', async () => {
    await say('/lang ru');
    await say('/closeall acc1');
    await say('1234');
    expect(last()).toBe(t('ru', 'msg.CLOSE_ALL_SENT', { count: '2' }));

    api.closeAll.mockResolvedValue({ message: 'plain message' });
    await say('/closeall acc1');
    await say('1234');
    expect(last()).toBe('plain message');
  });

  it('/status and /positions labels follow the language', async () => {
    api.getAccounts = jest.fn(async () => [{ id: 'acc1' }]);
    api.getBalance = jest.fn(async () => ({ equity: '100', asset: 'USDT', unrealizedPnl: '5' }));
    api.getLivePositions = jest.fn(async () => ({
      positions: [{ symbol: 'BTCUSDT', side: 'LONG', qty: '1', avgPrice: '10', markPrice: '11', unrealizedPnl: '1' }],
    }));
    await say('/lang ru');
    await say('/status');
    expect(last()).toBe(t('ru', 'fmt.status', { account: 'acc1', equity: '100', asset: 'USDT', pnl: '5' }));
    await say('/positions acc1');
    expect(last()).toContain(t('ru', 'fmt.live_position', { symbol: 'BTCUSDT', side: 'LONG', qty: '1', price: '10', mark: '11', pnl: '1' }));
  });

  describe('notification relay', () => {
    const event = {
      id: 5,
      kind: 'POSITION_ADOPTED',
      accountId: 'acc1',
      symbol: 'BTCUSDT',
      code: 'POSITION_ADOPTED',
      params: { symbol: 'BTCUSDT' },
      message: 'english fallback',
      createdAt: 0,
    };

    const relay = async (events: unknown[]) => {
      api.getNotifications.mockResolvedValueOnce([]); // seed tick
      await (bot as any).relayNotifications();
      api.getNotifications.mockResolvedValueOnce(events);
      await (bot as any).relayNotifications();
    };

    it('renders in the language chosen in each chat', async () => {
      await say('/lang ru');
      sent = [];
      await relay([event]);
      const text = (chat: number) => sent.find((m) => String(m.chat) === String(chat))!.text;
      expect(text(CHAT)).toBe(`[POSITION_ADOPTED] acc1 BTCUSDT: ${t('ru', 'msg.POSITION_ADOPTED', event.params)}`);
      expect(text(OTHER_CHAT)).toBe(`[POSITION_ADOPTED] acc1 BTCUSDT: ${t('en', 'msg.POSITION_ADOPTED', event.params)}`);
    });

    it('follows the language detected from the sender of the chat when /lang was never used', async () => {
      await say('/foo', { languageCode: 'ru' });
      sent = [];
      await relay([event]);
      const text = (chat: number) => sent.find((m) => String(m.chat) === String(chat))!.text;
      expect(text(CHAT)).toBe(`[POSITION_ADOPTED] acc1 BTCUSDT: ${t('ru', 'msg.POSITION_ADOPTED', event.params)}`);
      // a chat nobody has written in yet has nothing detected: English
      expect(text(OTHER_CHAT)).toBe(`[POSITION_ADOPTED] acc1 BTCUSDT: ${t('en', 'msg.POSITION_ADOPTED', event.params)}`);
    });

    it('an explicit /lang beats the detected language', async () => {
      await say('/lang en', { languageCode: 'ru' });
      sent = [];
      await relay([event]);
      const text = sent.find((m) => String(m.chat) === String(CHAT))!.text;
      expect(text).toBe(`[POSITION_ADOPTED] acc1 BTCUSDT: ${t('en', 'msg.POSITION_ADOPTED', event.params)}`);
    });

    it('falls back to the stored message for an unknown or missing code', async () => {
      await relay([{ ...event, code: 'FROM_THE_FUTURE' }, { ...event, id: 6, code: null }]);
      const texts = sent.filter((m) => String(m.chat) === String(CHAT)).map((m) => m.text);
      expect(texts).toEqual([
        '[POSITION_ADOPTED] acc1 BTCUSDT: english fallback',
        '[POSITION_ADOPTED] acc1 BTCUSDT: english fallback',
      ]);
    });
  });
});
