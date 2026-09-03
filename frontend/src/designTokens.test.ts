import { describe, it, expect } from 'vitest';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

/**
 * `tailwind.config.js` overrides exactly one font family: `sans` (Inter). There is no `serif`
 * definition and no serif webfont in the document head -- `frontend/index.html` loads Inter and
 * Manrope, nothing else.
 *
 * So `font-serif` never named a typeface this product owns. It fell through to Tailwind's stock
 * stack and rendered as `ui-serif, Georgia, Cambria, "Times New Roman", Times, serif` -- a face
 * that is never downloaded, was never chosen, and resolves differently on macOS, Windows and
 * Android. It had reached the page heading on Settings, Profile and Gmail Review, the goal name on
 * Goals, the section heading of every SectionCard, and the title of all five account modals, which
 * is why those surfaces visibly did not match the rest of the app.
 *
 * The rule this product actually follows is written down at the top of `index.html`: "Inter carries
 * the product UI; Manrope is headings-only and used on the marketing page." Marketing type reaches
 * Manrope through the `.m-*` classes in index.css, which name it explicitly. Nothing in the app UI
 * should be reaching for a family utility at all.
 *
 * Deliberately NOT extended to `font-mono`, which is equally undefined and equally falls through:
 * there it is the right answer. It is used for OTP inputs, UUIDs, layout fingerprints, hex colours
 * and raw error payloads, where any monospace face works and the stock stack is exactly what you
 * want. `font-serif` is the opposite -- it was not a typographic choice, it was a fallback nobody
 * noticed.
 *
 * If a display face is genuinely wanted in the app UI, define it in `tailwind.config.js` so it is a
 * real, loaded, versioned token -- then allow that token here.
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
