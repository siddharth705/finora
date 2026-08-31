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

  it('light.mutedInk has a real margin over light.muted, not just a token rename', () => {
    // Guards against a future edit accidentally setting mutedInk back to muted's exact value,
    // which would silently undo this fix while every call site still compiles. A margin of at
    // least +1.0 can't be satisfied by rounding noise or a near-identical color -- it forces the
    // fix to still be a real darkening, the same shape of regression warningInk already guards.
    const before = contrastRatio(light.muted, light.bg);
    const after = contrastRatio(light.mutedInk, light.bg);
    expect(after).toBeGreaterThan(before + 1.0);
  });

  it("dark.mutedInk intentionally equals dark.muted, since dark theme already clears AA", () => {
    // The inverse of the light-mode guard above: dark.muted already sits at ~7.3:1 (see palette.ts's
    // comment), so mutedInk correctly makes no change there -- same shape as dark.warningInk
    // equalling dark.warning. Pinned explicitly so the two guards can't be satisfied by accident in
    // opposite directions (e.g. a future edit that darkens dark.mutedInk too, which the AA-floor
    // test alone wouldn't catch since a darker color still clears 4.5:1).
    expect(dark.mutedInk).toBe(dark.muted);
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

  it.each([
    ['light', light],
    ['dark', dark],
  ])('%s: successInk clears WCAG AA (4.5:1) against successBg', (_name, p) => {
    expect(contrastRatio(p.successInk, p.successBg)).toBeGreaterThanOrEqual(AA_SMALL_TEXT);
  });

  it('light.successInk has a real margin over light.success, not just a token rename', () => {
    const before = contrastRatio(light.success, light.successBg);
    const after = contrastRatio(light.successInk, light.successBg);
    expect(after).toBeGreaterThan(before + 2.0);
  });

  it('dark.successInk intentionally equals dark.success, since dark theme already clears AA', () => {
    expect(dark.successInk).toBe(dark.success);
  });
});
