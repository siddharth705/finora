/**
 * The Finora mark: an F cut from a graphite square rather than raised on a brand-colour one --
 * "Quiet Mark". Kept as an independent copy of the user frontend's src/components/BrandMark.tsx
 * rather than a shared import, since admin-portal and frontend are separate builds with no
 * shared component package between them -- but the two are meant to stay pixel-identical.
 *
 * Two surfaces, two colour strategies here:
 *  - `variant="fixed"` (default): a fixed graphite square + paper F, or reversed via `invert`.
 *    Correct for Sidebar, which is dark by design regardless of the admin's light/dark
 *    preference -- the *toggling* `primary` token would be wrong here (dark graphite in light
 *    mode is invisible against an always-dark sidebar).
 *  - `variant="auto"`: square = `var(--color-ink)`, F = `var(--color-bg)` -- both already flip
 *    correctly with the `.dark` class, so the mark self-adjusts on a page that DOES participate
 *    in the toggle (Login, and the rest of the auth flow).
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
