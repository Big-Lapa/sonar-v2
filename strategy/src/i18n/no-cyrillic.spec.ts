import { execFileSync } from 'child_process';
import { readFileSync } from 'fs';
import { join } from 'path';

/**
 * Repo-wide guard: the project is English-first. Russian lives only in the `ru.json` dictionaries
 * (admin panel, Telegram bot); everything else — code, comments, logs, docs, tests — stays English.
 * Walks the git-tracked files, so local untracked material (worktrees, personal notes, .env) is ignored.
 */
const ROOT = join(__dirname, '../../..');
const CYRILLIC = /[\u0400-\u04FF]/;
const TEXT_EXT = /\.(ts|tsx|js|mjs|json|java|html|css|yml|yaml|sql|md|txt|py|xml|properties|example|ps1|sh)$|Dockerfile$/;
const ALLOWED = new Set(['strategy/src/i18n/ru.json', 'trading/src/main/resources/static/admin/i18n/ru.json']);

function trackedFiles(): string[] {
  const out = execFileSync('git', ['ls-files', '-z'], { cwd: ROOT, encoding: 'utf-8', maxBuffer: 64 * 1024 * 1024 });
  return out.split('\0').filter((f) => f && TEXT_EXT.test(f) && !f.endsWith('package-lock.json'));
}

describe('repository language', () => {
  it('has no Cyrillic outside the ru dictionaries', () => {
    const offenders: string[] = [];
    for (const rel of trackedFiles()) {
      if (ALLOWED.has(rel)) continue;
      const lines = readFileSync(join(ROOT, rel), 'utf-8').split('\n');
      lines.forEach((line, i) => {
        if (CYRILLIC.test(line)) offenders.push(`${rel}:${i + 1}`);
      });
    }
    expect(offenders).toEqual([]);
  });
});
