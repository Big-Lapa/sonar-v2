import { createHash, timingSafeEqual } from 'crypto';

/** Ticker alphabet — keep in sync with trading's Symbols.VALID (Symbols.java), which enforces the
 *  same rule server-side; this copy only exists so the bot can answer with a readable error. */
export const TICKER_RE = /^[A-Z0-9][A-Z0-9_-]{1,39}$/;

function idList(envName: string): string[] {
  return (process.env[envName] ?? '').split(',').map((s) => s.trim()).filter(Boolean);
}

/** Allowlist chat id gate (5: "Доступ — allowlist chat id"). */
export function isAllowedChat(chatId: number | string): boolean {
  return idList('TELEGRAM_ALLOWED_CHAT_IDS').includes(String(chatId));
}

/**
 * Who may issue commands: the chat must be allowlisted AND the sender must be a known person.
 * A chat id alone isn't enough — if an allowlisted chat is a group, every member of it would
 * otherwise control the accounts. With TELEGRAM_ALLOWED_USER_IDS set, the sender's user id must be
 * on it; without it, only private chats are accepted (there chat.id is the user's own id, so the
 * chat allowlist already names the person).
 */
export function isAllowedSender(chat: any, from: any): boolean {
  if (!chat || !isAllowedChat(chat.id)) return false;
  const users = idList('TELEGRAM_ALLOWED_USER_IDS');
  if (users.length > 0) return from?.id !== undefined && users.includes(String(from.id));
  return chat.type === 'private';
}

/** Constant-time PIN check; an unset PIN never matches. */
export function pinMatches(candidate: string): boolean {
  const pin = process.env.TELEGRAM_PIN ?? '';
  if (!pin) return false;
  const a = createHash('sha256').update(candidate).digest();
  const b = createHash('sha256').update(pin).digest();
  return timingSafeEqual(a, b);
}

export function pinConfigured(): boolean {
  return (process.env.TELEGRAM_PIN ?? '') !== '';
}
