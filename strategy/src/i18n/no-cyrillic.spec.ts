import { readdirSync, readFileSync, statSync } from 'fs';
import { join, relative, sep } from 'path';

/**
 * Repo-wide guard: the project is English-first. Russian lives only in the `ru.json` dictionaries
 * (admin panel, Telegram bot); everything else — code, comments, logs, docs, tests — stays English.
 */
const ROOT = join(__dirname, '../../..');
const CYRILLIC = /[\u0400-\u04FF]/;
const SKIP_DIRS = new Set(['node_modules', 'dist', 'target', '.git', '.claude', '.lavish', '.idea', '.vscode']);
const TEXT_EXT = /\.(ts|js|json|java|html|css|yml|yaml|sql|md|xml|properties|env|example|ps1|sh)$|Dockerfile$|\.env\.example$/;
const ALLOWED = new Set(['strategy/src/i18n/ru.json', 'trading/src/main/resources/static/admin/i18n/ru.json']);

function* walk(dir: string): Generator<string> {
  for (const name of readdirSync(dir)) {
    if (SKIP_DIRS.has(name)) continue;
    const full = join(dir, name);
    if (statSync(full).isDirectory()) yield* walk(full);
    else if (TEXT_EXT.test(name) && name !== 'package-lock.json') yield full;
  }
}

describe('repository language', () => {
  it('has no Cyrillic outside the ru dictionaries', () => {
    const offenders: string[] = [];
    for (const file of walk(ROOT)) {
      const rel = relative(ROOT, file).split(sep).join('/');
      if (ALLOWED.has(rel)) continue;
      const lines = readFileSync(file, 'utf-8').split('\n');
      lines.forEach((line, i) => {
        if (CYRILLIC.test(line)) offenders.push(`${rel}:${i + 1}`);
      });
    }
    expect(offenders).toEqual([]);
  });
});
