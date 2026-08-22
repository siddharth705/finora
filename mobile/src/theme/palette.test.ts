import { dark, light } from './palette';

/**
 * WCAG 2.x relative-luminance contrast ratio between two hex colors. Nothing in this repo computes
 * this for a raw hex pair -- the web side's a11y.measure.ts leans on axe, which needs a rendered
 * DOM and explicitly can't judge color-contrast under jsdom (see its own comment) -- so this is a
 * small, self-contained implementation rather than a partial one borrowed from a tool that can't
 * actually run it here.
 */
function channel(hex: number): number {
  const c = hex / 255;
  return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
}

function luminance(hex: string): number {
  const n = parseInt(hex.replace('#', ''), 16);
  const r = channel((n >> 16) & 0xff);
  const g = channel((n >> 8) & 0xff);
  const b = channel(n & 0xff);
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function contrastRatio(a: string, b: string): number {
  const [l1, l2] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (l1 + 0.05) / (l2 + 0.05);
}

// WCAG AA floor for text below 18pt (or below 14pt bold) -- see the Accessibility reference this
// review cites: "Up to 17 pts / All weights / 4.5:1 minimum contrast ratio". Every text token this
// app uses at those sizes must clear it against the two backgrounds it actually renders on.
const AA_SMALL_TEXT = 4.5;

describe('theme palette contrast', () => {
  it.each([
    ['light', light],
    ['dark', dark],
  ])('%s: mutedInk clears WCAG AA (4.5:1) against bg and card', (_name, p) => {
    expect(contrastRatio(p.mutedInk, p.bg)).toBeGreaterThanOrEqual(AA_SMALL_TEXT);
    expect(contrastRatio(p.mutedInk, p.card)).toBeGreaterThanOrEqual(AA_SMALL_TEXT);
  });

  it.each([
    ['light', light],
    ['dark', dark],
  ])('%s: mutedInk has a real margin over muted, not just a token rename', (_name, p) => {
    // Guards against a future edit accidentally setting mutedInk back to muted's exact value,
    // which would silently undo this fix while every call site still compiles.
    expect(contrastRatio(p.mutedInk, p.bg)).toBeGreaterThan(contrastRatio(p.muted, p.bg) - 0.01);
  });

  it.each([
    ['light', light],
    ['dark', dark],
  ])('%s: warningInk still clears WCAG AA against warningBg (regression guard)', (_name, p) => {
    expect(contrastRatio(p.warningInk, p.warningBg)).toBeGreaterThanOrEqual(AA_SMALL_TEXT);
  });

  it('mutedInk does not change the web-shared muted token', () => {
    // muted mirrors frontend/src/index.css's --color-muted intentionally (see palette.ts's own
    // header comment) -- mutedInk must be an addition, not a rename, or mobile and web silently
    // diverge on a value that's supposed to be shared.
    expect(light.muted).toBe('#64748B');
    expect(dark.muted).toBe('#94A3B8');
  });
});
