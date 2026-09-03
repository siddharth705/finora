import { describe, it, expect } from 'vitest';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

/**
 * Mirror of frontend/src/designTokens.test.ts -- see that file for the full reasoning.
 *
 * Short version: this app's `tailwind.config.js` overrides only `sans`, so `font-serif` names
 * nothing the design system owns and falls through to Tailwind's stock `Georgia/Times` stack -- a
 * face that is never loaded and renders differently per operating system. It had reached the
 * "Gmail receipt parsers" heading on Merchant Intelligence, which is how one admin heading ended up
 * in a serif while every other heading in the portal was Inter.
 *
 * Duplicated rather than shared because the two apps deliberately keep their design systems
 * separate; the anti-drift rule in this repo is that a fix to one prompts checking the other, which
 * is exactly what this pair encodes.
 *
 * `font-mono` is intentionally NOT covered: it is used correctly and heavily here for IDs,
 * fingerprints and raw payloads, where the stock monospace stack is the right answer.
 */
const SRC = join(__dirname);
const EXTENSIONS = ['.ts', '.tsx', '.css', '.html'];

function sourceFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((entry) => {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) return sourceFiles(full);
    return EXTENSIONS.some((ext) => entry.endsWith(ext)) ? [full] : [];
  });
}

describe('font family tokens', () => {
  it('never uses font-serif, which resolves to Georgia rather than to anything this app loads', () => {
    const offenders = sourceFiles(SRC)
      .filter((file) => !file.endsWith('designTokens.test.ts'))
      .flatMap((file) =>
        readFileSync(file, 'utf8')
          .split('\n')
          .map((line, i) => ({ file: file.slice(SRC.length + 1), line: i + 1, text: line }))
          .filter(({ text }) => /\bfont-serif\b/.test(text))
      )
      .map(({ file, line, text }) => `${file}:${line}: ${text.trim().slice(0, 100)}`);

    expect(offenders).toEqual([]);
  });
});
