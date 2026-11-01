import en from './en.json';
import ru from './ru.json';

export type Lang = 'en' | 'ru';
export const LANGS: readonly Lang[] = ['en', 'ru'];
export const DEFAULT_LANG: Lang = 'en';

const dictionaries: Record<Lang, Record<string, string>> = { en, ru };

const GROUP = /\[([^[\]]*)]/g;
const PARAM = /\{(\w+)\}/g;

export function isLang(value: string | undefined): value is Lang {
  return value === 'en' || value === 'ru';
}

/** Interface language for a Telegram client: Russian for `ru*`, English for everything else. */
export function langFromTelegram(code: string | undefined): Lang {
  return code?.toLowerCase().startsWith('ru') ? 'ru' : 'en';
}

export function hasKey(lang: Lang, key: string): boolean {
  return key in dictionaries[lang];
}

export type Params = Record<string, string | number | null | undefined>;

/**
 * `{name}` is a parameter; `[ ... {name} ... ]` is an optional group dropped when any parameter
 * inside it is missing (same template syntax as the backend's MessageCode and the admin panel).
 */
export function render(template: string, params: Params): string {
  const has = (name: string) => params[name] !== undefined && params[name] !== null;
  return template
    .replace(GROUP, (_m, inner: string) =>
      [...inner.matchAll(PARAM)].every((m) => has(m[1])) ? inner : '',
    )
    .replace(PARAM, (_m, name: string) => (has(name) ? String(params[name]) : ''));
}

/** A backend result: translated by its message code when we know it, else its stored English text. */
export function tMsg(lang: Lang, code: string | null | undefined, params: Params | null | undefined, fallback: string | null | undefined): string {
  return code && hasKey(lang, `msg.${code}`) ? t(lang, `msg.${code}`, params ?? {}) : (fallback ?? '');
}

/** Translate `key` into `lang`; falls back to English, then to the key itself. */
export function t(lang: Lang, key: string, params: Params = {}): string {
  const template = dictionaries[lang][key] ?? dictionaries[DEFAULT_LANG][key];
  return template === undefined ? key : render(template, params);
}
