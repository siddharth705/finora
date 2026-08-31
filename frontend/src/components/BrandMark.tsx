/**
 * The Finora mark: an F cut from a graphite square rather than raised on a brand-colour one —
 * "Quiet Mark". Single source of truth so Nav, Sidebar, PublicLayout and the auth pages render
 * the same glyph instead of independent "F in a box" implementations drifting apart, which is
 * what the three separate copies of this (a hardcoded blue div, a hardcoded blue gradient div,
 * and a leftover teal/blue gradient PNG) had already done before this component existed.
 *
 * Three surfaces, three colour strategies here:
 *  - `variant="fixed"` (default): a fixed graphite square + paper F, or reversed via `invert`.
 *    Correct for surfaces that never change with the app's light/dark toggle — the marketing
 *    scope (light-only by design) and PublicLayout/Sidebar (dark by design regardless of the
 *    user's preference, so the *toggling* `primary` token would be wrong here: dark graphite in
 *    light mode is invisible against an always-dark page).
 *  - `variant="auto"`: square = `var(--color-ink)`, F = `var(--color-bg)` — both already flip
 *    correctly with the `.dark` class, so the mark self-adjusts on a normal page that DOES
 *    participate in the toggle (the auth pages), with no theme-reading logic needed here.
 */
export function BrandMark({ size = 32, invert = false, variant = 'fixed', className = '' }: {
  size?: number;
  invert?: boolean;
  variant?: 'fixed' | 'auto';
  className?: string;
}) {
  const square = variant === 'auto' ? 'rgb(var(--color-ink))' : invert ? '#F4F1EC' : '#262A33';
  const glyph = variant === 'auto' ? 'var(--color-bg)' : invert ? '#262A33' : '#F4F1EC';
  return (
    <svg width={size} height={size} viewBox="0 0 100 100" className={className} aria-hidden="true">
      <rect x="4" y="4" width="92" height="92" rx="22" fill={square} />
      <rect x="32" y="26" width="13" height="48" rx="4" fill={glyph} />
      <rect x="32" y="26" width="34" height="13" rx="4" fill={glyph} />
      <rect x="32" y="50" width="25" height="12" rx="4" fill={glyph} />
    </svg>
  );
}
