/**
 * The same palette the web app defines as CSS custom properties in frontend/src/index.css, lifted
 * into plain objects (React Native has no CSS variables). Both modes are here because the app
 * honors the system setting by default and offers a manual override -- screens read colors through
 * useTheme() rather than importing one palette directly, so neither costs anything per screen.
 *
 * The web stores --color-ink/--color-primary as unitless "R G B" triplets purely so Tailwind can
 * apply opacity modifiers to them; that's a Tailwind implementation detail, so they're plain hex
 * here like every other color.
 *
 * Split out of theme/index.ts when the manual override landed: ThemeContext needs these values,
 * and theme/index.ts re-exports ThemeContext's hooks, so leaving them together made a cycle.
 * Nothing imports this file directly -- `../theme` is still the one entry point.
 */
export const light = {
  bg: '#F8FAFC',
  card: '#ffffff',
  border: '#E6EAF2',
  ink: '#0F172A',
  muted: '#64748B',
  // `muted` (#64748B on this screen's #F8FAFC background) sits at ~4.55:1 -- just over WCAG AA's
  // 4.5:1 floor for the 11-13pt sizes it's used at (transaction dates, hints, goal metadata), with
  // almost no margin for a darker background variant or a slightly-off display. Same shape of
  // problem as `warningInk` below, and the same fix: a separate token rather than a change to
  // `muted` itself, since that value is shared with frontend/src/index.css's --color-muted and is
  // fine in the roles it's actually used for there. This slate-600 clears 7.25:1 on the same
  // background -- real margin, not just over the line.
  mutedInk: '#475569',
  primary: '#262A33',
  primaryDark: '#15171C',
  primaryLight: '#F4F1EC',
  // Text/icon color for anything drawn ON a primary-filled surface. Was safe to hardcode '#fff'
  // at every call site while primary was always a mid-to-dark blue in both themes; now that dark
  // mode's primary is light paper, white text on it is nearly invisible, so this has to flip
  // opposite to primary itself -- see frontend/src/index.css's --color-on-primary for the web
  // equivalent of the same problem.
  onPrimary: '#FFFFFF',
  success: '#16a34a',
  successBg: '#dcfce7',
  // `success` on `successBg` sits at ~3.00:1 -- under WCAG AA's 4.5:1 floor, the same shape of
  // problem warningInk exists to fix just below. This green-800 clears ~6.49:1 on the same
  // ground -- real margin, not just over the line. Needed for OfflineBanner's transient
  // "back online" state.
  successInk: '#166534',
  danger: '#dc2626',
  dangerBg: '#fee2e2',
  warning: '#d97706',
  warningBg: '#fef3c7',
  // The shared `warning` tone is tuned for icons and borders; as text on `warningBg` it only
  // reaches 2.86:1, well under WCAG AA's 4.5:1. This darker amber hits 6.37:1 on the same ground.
  // A separate token rather than a change to `warning` itself, since that value is shared with
  // the web app and is fine in the roles it's actually used for there.
  warningInk: '#92400e',
  inputBg: '#FFFFFF',
};

export const dark: typeof light = {
  bg: '#0B1220',
  card: '#151C2C',
  border: '#253044',
  ink: '#E2E8F0',
  muted: '#94A3B8',
  // Dark theme's `muted` already clears AA comfortably (~7.3:1 on this screen's #0B1220
  // background), so this is the same value as `muted` -- same reasoning as dark.warningInk below.
  mutedInk: '#94A3B8',
  primary: '#F4F1EC',
  primaryDark: '#DAD5C9',
  primaryLight: '#26241F',
  onPrimary: '#15171C',
  success: '#22c55e',
  successBg: '#12301f',
  // Dark theme's success already clears AA comfortably on successBg (~6.28:1), so this is the
  // same value as success -- same reasoning as dark.warningInk/dark.mutedInk above.
  successInk: '#22c55e',
  danger: '#f87171',
  dangerBg: '#3a1518',
  warning: '#fbbf24',
  warningBg: '#3a2a0a',
  // Dark theme already clears AA comfortably (8.30:1), so this is the same value as `warning`.
  warningInk: '#fbbf24',
  inputBg: '#0B1220',
};

export type Palette = typeof light;

export const radius = {
  md: 8,
  lg: 12,
  xl: 16,
};

export const spacing = {
  xs: 4,
  sm: 8,
  md: 16,
  lg: 24,
  xl: 32,
};
