import { isAllowedSender, pinConfigured, pinMatches } from './access-guard';

describe('access-guard', () => {
  const env = { ...process.env };
  afterEach(() => {
    process.env = { ...env };
  });

  describe('isAllowedSender', () => {
    beforeEach(() => {
      process.env.TELEGRAM_ALLOWED_CHAT_IDS = '42, -100';
      delete process.env.TELEGRAM_ALLOWED_USER_IDS;
    });

    it('rejects a chat that is not allowlisted', () => {
      expect(isAllowedSender({ id: 7, type: 'private' }, { id: 7 })).toBe(false);
      expect(isAllowedSender(undefined, { id: 42 })).toBe(false);
    });

    it('without a user list accepts only private chats', () => {
      expect(isAllowedSender({ id: 42, type: 'private' }, { id: 42 })).toBe(true);
      expect(isAllowedSender({ id: -100, type: 'group' }, { id: 42 })).toBe(false);
    });

    it('with a user list requires the sender to be on it', () => {
      process.env.TELEGRAM_ALLOWED_USER_IDS = '42';
      expect(isAllowedSender({ id: -100, type: 'group' }, { id: 42 })).toBe(true);
      expect(isAllowedSender({ id: -100, type: 'group' }, { id: 99 })).toBe(false);
      expect(isAllowedSender({ id: -100, type: 'group' }, undefined)).toBe(false);
    });
  });

  describe('pinMatches', () => {
    it('matches only the configured PIN', () => {
      process.env.TELEGRAM_PIN = '1234';
      expect(pinConfigured()).toBe(true);
      expect(pinMatches('1234')).toBe(true);
      expect(pinMatches('12345')).toBe(false);
      expect(pinMatches('')).toBe(false);
    });

    it('never matches when no PIN is configured', () => {
      delete process.env.TELEGRAM_PIN;
      expect(pinConfigured()).toBe(false);
      expect(pinMatches('')).toBe(false);
    });
  });
});
