import axe from 'axe-core';

/**
 * Runs axe against an already-rendered container and returns its violations.
 *
 * Why axe rather than eslint-plugin-jsx-a11y, which the proposal originally suggested: no release
 * of that plugin supports ESLint 10, which is what both web apps are on (its peer range stops at
 * 9). Forcing it in would mean an unsupported plugin, `--legacy-peer-deps` in CI, and no
 * confidence it keeps working. axe has no such coupling, and it is the better measurement anyway:
 * it inspects the DOM that actually rendered rather than JSX patterns, so it catches problems that
 * come from composition rather than from a single element.
 *
 * KNOWN LIMIT, stated so the numbers are not over-read: jsdom has no layout engine, so rules that
 * need geometry or computed colour cannot run here. `color-contrast` is the notable one. A clean
 * result from this file means "no violations that are detectable without a browser", not "this
 * page is accessible". A real audit needs a browser and a person.
 */
export async function axeViolations(container: HTMLElement): Promise<axe.Result[]> {
  const results = await axe.run(container, {
    // Reporting only what a browser-less run can actually judge.
    rules: { 'color-contrast': { enabled: false } },
    resultTypes: ['violations'],
  });
  return results.violations;
}

/** One line per violation, including each affected node's selector — enough to find and fix the
 *  element straight from a CI log, without re-running anything locally. */
export function summarise(violations: axe.Result[]): string[] {
  return violations.map((v) => {
    const where = v.nodes.map((n) => n.target.join(' ')).join('; ');
    return `${v.impact ?? 'unknown'}: ${v.id} (${v.nodes.length}) — ${v.help} [${where}]`;
  });
}
