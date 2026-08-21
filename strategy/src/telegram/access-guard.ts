/** Allowlist chat id gate (5: "Доступ — allowlist chat id"). */
export function isAllowedChat(chatId: number | string): boolean {
  const raw = process.env.TELEGRAM_ALLOWED_CHAT_IDS ?? '';
  const allowed = raw.split(',').map((s) => s.trim()).filter(Boolean);
  return allowed.includes(String(chatId));
}
