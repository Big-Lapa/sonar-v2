import { TelegramBotService } from './telegram-bot.service';
import { TelegramClientService } from './telegram-client.service';
import { TradingApiService } from '../trading-client/trading-api.service';

const CHAT = 42;
const MIN = 60_000;

describe('TelegramBotService PIN flow', () => {
  const env = { ...process.env };
  let sent: string[];
  let deleted: number[];
  let nextMessageId: number;
  let positions: { id: number }[];
  let api: Record<string, jest.Mock>;
  let bot: TelegramBotService;
  let now: number;

  const say = (text: string) =>
    (bot as any).handleMessage({ chat: { id: CHAT, type: 'private' }, from: { id: CHAT }, text, message_id: ++nextMessageId });
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
    deleted = [];
    nextMessageId = 0;
    positions = [{ id: 7 }];
    api = {
      getOpenPositions: jest.fn(async () => positions),
      moveStop: jest.fn(async () => ({ message: 'moved' })),
      clearAutoPause: jest.fn(async () => ({ wasPaused: true })),
      closeAll: jest.fn(async (a: string) => ({ message: `closed ${a}` })),
    };
    const telegram = {
      sendMessage: jest.fn(async (_c: number, t: string) => void sent.push(t)),
      deleteMessage: jest.fn(async (_c: number, id: number) => void deleted.push(id)),
    };
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
    expect(last()).toBe('confirm PIN for close-all acc1');
    await say('1234');
    expect(api.closeAll).toHaveBeenCalledWith('acc1');
    expect(last()).toBe('closed acc1');
  });

  it('reports an action failure after the PIN instead of going silent', async () => {
    api.moveStop.mockRejectedValue(new Error('Request failed with status code 400'));
    await say('/move BTCUSDT sl 100');
    await say('1234');
    expect(last()).toBe('error: Request failed with status code 400');

    api.clearAutoPause.mockRejectedValue(new Error('Request failed with status code 404'));
    await say('/unpause acc1');
    await say('1234');
    expect(last()).toBe('error: Request failed with status code 404');
  });

  it('a command sent instead of the PIN cancels the prompt without counting a failure', async () => {
    await say('/closeall acc1');
    await say('/help');
    expect(sent).toContain('close-all acc1: confirmation cancelled');
    expect(last()).toMatch(/^Commands/);
    expect(api.closeAll).not.toHaveBeenCalled();

    // Three wrong PINs are still needed for a lockout — the cancelled prompt didn't count.
    await wrongPin();
    expect(last()).toContain('attempts left: 2');
  });

  it('does not echo plain text back as an unknown command', async () => {
    await say('1234');
    expect(last()).toBe('unknown command. /help for the command list');
    await say('/foo');
    expect(last()).toBe('unknown command /foo. /help for the command list');
  });

  it('locks PIN commands after three wrong PINs in a row', async () => {
    await wrongPin();
    await wrongPin();
    await wrongPin();
    expect(last()).toContain('locked for 15 min');

    await say('/closeall acc1');
    expect(last()).toContain('locked for another 15 min');

    now += 16 * MIN;
    await say('/closeall acc1');
    expect(last()).toBe('confirm PIN for close-all acc1');
  });

  it('forgets wrong PINs older than the failure window', async () => {
    await wrongPin();
    await wrongPin();
    expect(last()).toContain('attempts left: 1');

    now += 16 * MIN;
    await wrongPin();
    expect(last()).toContain('attempts left: 2');
  });

  it('a right PIN resets the failure count', async () => {
    await wrongPin();
    await wrongPin();
    await say('/closeall acc1');
    await say('1234');
    await wrongPin();
    expect(last()).toContain('attempts left: 2');
  });

  it('cancels an expired PIN prompt', async () => {
    await say('/closeall acc1');
    now += 61_000;
    await say('1234');
    expect(last()).toBe('PIN expired, action cancelled');
    expect(api.closeAll).not.toHaveBeenCalled();
  });

  it('/move re-reads positions after the PIN', async () => {
    await say('/move BTCUSDT sl 100');
    positions = [];
    await say('1234');
    expect(last()).toBe('no open position for BTCUSDT');
    expect(api.moveStop).not.toHaveBeenCalled();
  });

  it('/move with no open position fails before asking for the PIN', async () => {
    positions = [];
    await say('/move BTCUSDT sl 100');
    expect(last()).toBe('no open position for BTCUSDT');
    expect(sent.some((t) => t.startsWith('confirm PIN'))).toBe(false);
  });

  it('refuses PIN commands when no PIN is configured', async () => {
    delete process.env.TELEGRAM_PIN;
    await say('/unpause acc1');
    expect(last()).toBe('TELEGRAM_PIN is not set — PIN commands are disabled');
    await say('1234');
    expect(api.clearAutoPause).not.toHaveBeenCalled();
  });

  it('in a group only the sender who asked can answer the PIN prompt', async () => {
    process.env.TELEGRAM_ALLOWED_CHAT_IDS = '-100';
    process.env.TELEGRAM_ALLOWED_USER_IDS = '1,2';
    const sayAs = (from: number, text: string) =>
      (bot as any).handleMessage({ chat: { id: -100, type: 'group' }, from: { id: from }, text, message_id: ++nextMessageId });
    await sayAs(1, '/closeall acc1');
    await sayAs(2, '1234');
    expect(api.closeAll).not.toHaveBeenCalled();
    await sayAs(1, '1234');
    expect(api.closeAll).toHaveBeenCalledWith('acc1');
  });

  it('deletes the message carrying the PIN, right or wrong, but not commands', async () => {
    await say('/closeall acc1'); // message 1
    await say('1234'); // message 2 — the PIN
    await say('/closeall acc1'); // message 3
    await say('0000'); // message 4 — wrong PIN
    expect(deleted).toEqual([2, 4]);
  });

  it('counts wrong PINs per user across chats, not per chat', async () => {
    process.env.TELEGRAM_ALLOWED_CHAT_IDS = '-100,-200';
    process.env.TELEGRAM_ALLOWED_USER_IDS = '1';
    const sayIn = (chat: number, text: string) =>
      (bot as any).handleMessage({ chat: { id: chat, type: 'group' }, from: { id: 1 }, text, message_id: ++nextMessageId });
    for (const chat of [-100, -200, -100]) {
      await sayIn(chat, '/closeall acc1');
      await sayIn(chat, '0000');
    }
    expect(last()).toContain('locked for 15 min');
    await sayIn(-200, '/closeall acc1');
    expect(last()).toContain('locked for another 15 min');
  });

  it('a PIN prompt opened before the lockout cannot be answered after it', async () => {
    process.env.TELEGRAM_ALLOWED_CHAT_IDS = '-100,-200';
    process.env.TELEGRAM_ALLOWED_USER_IDS = '1';
    const sayIn = (chat: number, text: string) =>
      (bot as any).handleMessage({ chat: { id: chat, type: 'group' }, from: { id: 1 }, text, message_id: ++nextMessageId });
    await sayIn(-100, '/closeall acc1');
    await sayIn(-100, '0000');
    await sayIn(-100, '/closeall acc1');
    await sayIn(-100, '0000');
    await sayIn(-200, '/closeall acc2'); // prompt opened while still unlocked
    await sayIn(-100, '/closeall acc1');
    await sayIn(-100, '0000');
    expect(last()).toContain('locked for 15 min');

    await sayIn(-200, '1234');
    expect(api.closeAll).not.toHaveBeenCalled();
    expect(last()).toContain('locked for another 15 min');
  });
});
