import { readFileSync, readdirSync, statSync } from 'fs';
import { join } from 'path';
import { describe, expect, it } from 'vitest';

/**
 * Keeps the brand in the token layer, where a rebrand is a one-file change.
 *
 * This exists because of a real failure. When the palette moved from indigo to blue, every
 * token-driven surface followed instantly -- and eight files did not, because they had hardcoded
 * `indigo-400`, `bg-indigo-500/10` and one `'#6366f1'` literal. Those bypass the CSS custom
 * properties entirely, so they silently kept the old brand while the rest of the product changed
 * around them. Nothing failed; it just looked wrong in eight places.
 *
 * The rule: reach for a semantic token (`primary`, `primary-dark`, `primary-light`, `ink`,
 * `muted`, `border`) rather than a palette colour or a hex literal. Then the next rebrand is
 * index.css and nothing else.
 *
 * Scoped to brand colours only. Semantic colours (success/danger/warning) and the deliberately
 * fixed illustration palettes are not brand, and chart series need literal colours by nature --
 * see ALLOWED below.
 */
const SRC = join(__dirname);

/**
 * Files that legitimately carry literal colours.
 *
 * The landing page owns its own `--m-*` marketing scope and a hand-drawn product illustration
 * whose chart series must be literal; chart components elsewhere are the same case. These are
 * exempt from the brand-token rule, not from review.
 */
const ALLOWED = [
  'pages/landing/',   // marketing scope + the dashboard illustration's chart colours
  'theme-tokens.test.ts',
];

/**
 * Brand-family colours that must come from a token instead.
 *
 * Indigo and violet only -- those were the old brand hues, so a literal one is almost certainly a
 * missed rebrand. `purple` is deliberately NOT here: it appears in categorical sets alongside
 * orange, blue and green (feature icons on Login/Register, the Savings Rate tile on Dashboard),
 * where the point is that each item is a DIFFERENT colour. Banning it would push those onto the
 * brand token and collapse a palette whose whole job is to distinguish. Categorical colour and
 * brand colour are different things and this list only governs the second.
 */
const BANNED = [
  /\b(?:from|to|via|bg|text|border|ring|fill|stroke|shadow)-(?:indigo|violet)-\d{2,3}\b/,
  /#6366f1\b/i,
  /#4f46e5\b/i,
  /#818cf8\b/i,
  /#eef0fd\b/i,
];

function sourceFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((entry) => {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) return sourceFiles(full);
    return /\.tsx?$/.test(entry) ? [full] : [];
  });
}

describe('brand colours live in the token layer', () => {
  it('finds source files to check', () => {
    expect(sourceFiles(SRC).length).toBeGreaterThan(20);
  });

  it('uses no hardcoded brand colour outside the allowed scopes', () => {
    const offenders: string[] = [];

    for (const file of sourceFiles(SRC)) {
      const rel = file.replace(SRC, '').replace(/\\/g, '/').replace(/^\//, '');
      if (ALLOWED.some((a) => rel.startsWith(a) || rel === a)) continue;

      readFileSync(file, 'utf8').split('\n').forEach((line, i) => {
        for (const pattern of BANNED) {
          const hit = pattern.exec(line);
          if (hit) offenders.push(`${rel}:${i + 1}  ${hit[0]}`);
        }
      });
    }

    expect(
      offenders,
      'Hardcoded brand colours bypass the token layer and survive a rebrand. ' +
        'Use primary / primary-dark / primary-light instead — see src/index.css.'
    ).toEqual([]);
  });
});
