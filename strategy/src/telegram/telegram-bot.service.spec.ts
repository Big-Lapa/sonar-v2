import { TelegramBotService } from './telegram-bot.service';
import { TelegramClientService } from './telegram-client.service';
import { TradingApiService } from '../trading-client/trading-api.service';

const CHAT = 42;
const MIN = 60_000;

describe('TelegramBotService PIN flow', () => {
  const env = { ...process.env };
  let sent: string[];
  let positions: { id: number }[];
  let api: Record<string, jest.Mock>;
  let bot: TelegramBotService;
  let now: number;

  const say = (text: string) =>
    (bot as any).handleMessage({ chat: { id: CHAT, type: 'private' }, from: { id: CHAT }, text });
  const last = () => sent[sent.length - 1];
  const wrongPin = async () => {
    await say('/closeall acc1');
    await say('0000');
  };

  beforeEach(() => {
    process.env.TELEGRAM_ALLOWED_CHAT_IDS = String(CHAT);
    process.env.TELEGRAM_PIN = '1234';
    now = 1_000_000_000;
    jest.spyOn(Date, 'now').mockImplementation(() => now);
    sent = [];
    positions = [{ id: 7 }];
    api = {
      getOpenPositions: jest.fn(async () => positions),
      moveStop: jest.fn(async () => ({ message: 'moved' })),
      clearAutoPause: jest.fn(async () => ({ wasPaused: true })),
      closeAll: jest.fn(async (a: string) => ({ message: `closed ${a}` })),
    };
    const telegram = { sendMessage: jest.fn(async (_c: number, t: string) => void sent.push(t)) };
    bot = new TelegramBotService(
      telegram as unknown as TelegramClientService,
      api as unknown as TradingApiService,
    );
  });

  afterEach(() => {
    jest.restoreAllMocks();
    process.env = { ...env };
  });

  it('runs a PIN-gated command after the right PIN', async () => {
    await say('/closeall acc1');
    expect(last()).toBe('подтверди PIN для close-all acc1');
    await say('1234');
    expect(api.closeAll).toHaveBeenCalledWith('acc1');
    expect(last()).toBe('closed acc1');
  });

  it('reports an action failure after the PIN instead of going silent', async () => {
    api.moveStop.mockRejectedValue(new Error('Request failed with status code 400'));
    await say('/move BTCUSDT sl 100');
    await say('1234');
    expect(last()).toBe('ошибка: Request failed with status code 400');

    api.clearAutoPause.mockRejectedValue(new Error('Request failed with status code 404'));
    await say('/unpause acc1');
    await say('1234');
    expect(last()).toBe('ошибка: Request failed with status code 404');
  });

  it('a command sent instead of the PIN cancels the prompt without counting a failure', async () => {
    await say('/closeall acc1');
    await say('/help');
    expect(sent).toContain('close-all acc1: подтверждение отменено');
    expect(last()).toMatch(/^Команды/);
    expect(api.closeAll).not.toHaveBeenCalled();

    // Three wrong PINs are still needed for a lockout — the cancelled prompt didn't count.
    await wrongPin();
    expect(last()).toContain('осталось попыток: 2');
  });

  it('does not echo plain text back as an unknown command', async () => {
    await say('1234');
    expect(last()).toBe('неизвестная команда. /help — список команд');
    await say('/foo');
    expect(last()).toBe('неизвестная команда /foo. /help — список команд');
  });

  it('locks PIN commands after three wrong PINs in a row', async () => {
    await wrongPin();
    await wrongPin();
    await wrongPin();
    expect(last()).toContain('заблокированы на 15 мин');

    await say('/closeall acc1');
    expect(last()).toContain('заблокированы ещё 15 мин');

    now += 16 * MIN;
    await say('/closeall acc1');
    expect(last()).toBe('подтверди PIN для close-all acc1');
  });

  it('forgets wrong PINs older than the failure window', async () => {
    await wrongPin();
    await wrongPin();
    expect(last()).toContain('осталось попыток: 1');

    now += 16 * MIN;
    await wrongPin();
    expect(last()).toContain('осталось попыток: 2');
  });

  it('a right PIN resets the failure count', async () => {
    await wrongPin();
    await wrongPin();
    await say('/closeall acc1');
    await say('1234');
    await wrongPin();
    expect(last()).toContain('осталось попыток: 2');
  });

  it('cancels an expired PIN prompt', async () => {
    await say('/closeall acc1');
    now += 61_000;
    await say('1234');
    expect(last()).toBe('PIN истёк, действие отменено');
    expect(api.closeAll).not.toHaveBeenCalled();
  });

  it('/move re-reads positions after the PIN', async () => {
    await say('/move BTCUSDT sl 100');
    positions = [];
    await say('1234');
    expect(last()).toBe('нет открытой позиции по BTCUSDT');
    expect(api.moveStop).not.toHaveBeenCalled();
  });

  it('/move with no open position fails before asking for the PIN', async () => {
    positions = [];
    await say('/move BTCUSDT sl 100');
    expect(last()).toBe('нет открытой позиции по BTCUSDT');
    expect(sent.some((t) => t.startsWith('подтверди PIN'))).toBe(false);
  });

  it('refuses PIN commands when no PIN is configured', async () => {
    delete process.env.TELEGRAM_PIN;
    await say('/unpause acc1');
    expect(last()).toBe('TELEGRAM_PIN не задан — команды с PIN отключены');
    await say('1234');
    expect(api.clearAutoPause).not.toHaveBeenCalled();
  });

  it('in a group only the sender who asked can answer the PIN prompt', async () => {
    process.env.TELEGRAM_ALLOWED_CHAT_IDS = '-100';
    process.env.TELEGRAM_ALLOWED_USER_IDS = '1,2';
    const sayAs = (from: number, text: string) =>
      (bot as any).handleMessage({ chat: { id: -100, type: 'group' }, from: { id: from }, text });
    await sayAs(1, '/closeall acc1');
    await sayAs(2, '1234');
    expect(api.closeAll).not.toHaveBeenCalled();
    await sayAs(1, '1234');
    expect(api.closeAll).toHaveBeenCalledWith('acc1');
  });
});
