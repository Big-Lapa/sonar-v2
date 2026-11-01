import { readFileSync } from 'fs';
import { join } from 'path';
import { LANGS, langFromTelegram, t } from './index';

const CYRILLIC = /[\u0400-\u04FF]/;
const PLACEHOLDER = /\{(\w+)\}/g;

const load = (lang: string): Record<string, string> =>
  JSON.parse(readFileSync(join(__dirname, `${lang}.json`), 'utf-8'));
const placeholders = (s: string) => new Set([...s.matchAll(PLACEHOLDER)].map((m) => m[1]));

describe('bot dictionaries', () => {
  const en = load('en');
  const ru = load('ru');

  it('support exactly en and ru', () => {
    expect([...LANGS].sort()).toEqual(['en', 'ru']);
  });

  it('have the same keys', () => {
    expect(Object.keys(ru).sort()).toEqual(Object.keys(en).sort());
  });

  it('have the same placeholders per key', () => {
    for (const key of Object.keys(en)) {
      expect([...placeholders(ru[key])].sort()).toEqual([...placeholders(en[key])].sort());
    }
  });

  it('keep Cyrillic out of en and actually translate ru', () => {
    for (const [k, v] of Object.entries(en)) expect({ k, cyrillic: CYRILLIC.test(v) }).toEqual({ k, cyrillic: false });
    const translated = Object.values(ru).filter((v) => CYRILLIC.test(v)).length;
    expect(translated).toBeGreaterThan(Object.keys(ru).length / 2);
  });

  it('translate every backend message code that the admin panel translates', () => {
    const admin = JSON.parse(
      readFileSync(join(__dirname, '../../../trading/src/main/resources/static/admin/i18n/en.json'), 'utf-8'),
    ) as Record<string, string>;
    const backendKeys = Object.keys(admin).filter((k) => k.startsWith('msg.'));
    expect(backendKeys.length).toBeGreaterThan(0);
    for (const key of backendKeys) expect(en).toHaveProperty([key]);
  });
});

describe('t()', () => {
  it('interpolates {params}', () => {
    expect(t('en', 'pin.confirm', { description: 'close-all acc1' })).toBe('confirm PIN for close-all acc1');
  });

  it('returns the other language for the same key', () => {
    expect(t('ru', 'pin.expired')).toMatch(CYRILLIC);
    expect(t('en', 'pin.expired')).toBe('PIN expired, action cancelled');
  });

  it('falls back to English, then to the key itself, for an unknown key', () => {
    expect(t('ru', 'no.such.key')).toBe('no.such.key');
  });
});

describe('langFromTelegram()', () => {
  it.each([
    ['ru', 'ru'],
    ['ru-RU', 'ru'],
    ['en', 'en'],
    ['en-GB', 'en'],
    ['de', 'en'],
    [undefined, 'en'],
  ])('%s -> %s', (code, expected) => {
    expect(langFromTelegram(code)).toBe(expected);
  });
});
