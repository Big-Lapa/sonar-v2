import { readFileSync } from 'fs';
import { join } from 'path';
import { runInNewContext } from 'vm';
import { render } from './index';
import en from './en.json';
import ru from './ru.json';

/**
 * The template syntax is implemented three times (Java Msg, this bot, the admin panel's i18n.js).
 * One shared case list keeps them from drifting; the Java side runs it in MsgTest.
 */
const REPO = join(__dirname, '../../..');
const CASES: { name: string; template: string; params: Record<string, string>; expected: string }[] = JSON.parse(
  readFileSync(join(REPO, 'trading/src/test/resources/i18n/render-cases.json'), 'utf-8'),
);
const ADMIN = join(REPO, 'trading/src/main/resources/static/admin');

/** Load the browser script into a stub window and hand back its I18N object. */
function loadAdminRuntime(): { render: (template: string, params: Record<string, string>) => string } {
  const window: any = { addEventListener: () => undefined, dispatchEvent: () => true };
  const document: any = {
    readyState: 'complete',
    documentElement: {},
    querySelectorAll: () => [],
    addEventListener: () => undefined,
  };
  const ctx = {
    window,
    document,
    navigator: { language: 'en' },
    localStorage: { getItem: () => null, setItem: () => undefined },
    fetch: async () => ({ ok: false }),
    CustomEvent: class {},
  };
  runInNewContext(readFileSync(join(ADMIN, 'i18n.js'), 'utf-8'), ctx);
  return window.I18N;
}

describe('template syntax, shared cases', () => {
  it('bot renderer', () => {
    for (const c of CASES) expect(render(c.template, c.params)).toBe(c.expected);
  });

  it('admin renderer (i18n.js)', () => {
    const admin = loadAdminRuntime();
    for (const c of CASES) expect(admin.render(c.template, c.params)).toBe(c.expected);
  });
});

describe('bot dictionaries vs admin dictionaries', () => {
  const adminDict = (lang: string): Record<string, string> =>
    JSON.parse(readFileSync(join(ADMIN, `i18n/${lang}.json`), 'utf-8'));

  it.each([
    ['en', en],
    ['ru', ru],
  ])('%s: every msg.* entry is identical to the admin panel one', (lang, bot) => {
    const admin = adminDict(lang);
    const botMsgs = Object.keys(bot).filter((k) => k.startsWith('msg.'));
    const adminMsgs = Object.keys(admin).filter((k) => k.startsWith('msg.'));
    expect(botMsgs.sort()).toEqual(adminMsgs.sort());
    for (const k of botMsgs) expect((bot as Record<string, string>)[k]).toBe(admin[k]);
  });

  it('en and ru bot dictionaries have the same keys and placeholders', () => {
    expect(Object.keys(ru).sort()).toEqual(Object.keys(en).sort());
    const ph = (s: string) => [...s.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort();
    for (const k of Object.keys(en)) expect(ph((ru as Record<string, string>)[k])).toEqual(ph((en as Record<string, string>)[k]));
  });
});
