import { useColorScheme } from 'react-native';

/**
 * The same palette the web app defines as CSS custom properties in frontend/src/index.css, lifted
 * into plain objects (React Native has no CSS variables). Both modes are here because
 * app.config.ts declares userInterfaceStyle: 'automatic' -- screens read colors through
 * useTheme() rather than importing one palette directly, so honoring the system setting costs
 * nothing per screen and doesn't need retrofitting later.
 *
 * The web stores --color-ink/--color-primary as unitless "R G B" triplets purely so Tailwind can
 * apply opacity modifiers to them; that's a Tailwind implementation detail, so they're plain hex
 * here like every other color.
 */
const light = {
  bg: '#f4f5fb',
  card: '#ffffff',
  border: '#e7e8f2',
  ink: '#1b1e2b',
  muted: '#6b7280',
  primary: '#6366f1',
  primaryDark: '#4f46e5',
  primaryLight: '#eef0fd',
  success: '#16a34a',
  successBg: '#dcfce7',
  danger: '#dc2626',
  dangerBg: '#fee2e2',
  warning: '#d97706',
  warningBg: '#fef3c7',
  inputBg: '#ffffff',
};

const dark: typeof light = {
  bg: '#0d0e1a',
  card: '#171933',
  border: '#2a2d4d',
  ink: '#e8e9f3',
  muted: '#8b8fa8',
  primary: '#818cf8',
  primaryDark: '#6366f1',
  primaryLight: '#22254a',
  success: '#22c55e',
  successBg: '#12301f',
  danger: '#f87171',
  dangerBg: '#3a1518',
  warning: '#fbbf24',
  warningBg: '#3a2a0a',
  inputBg: '#0d0e1a',
};

export type Palette = typeof light;

export function useTheme(): Palette {
  return useColorScheme() === 'dark' ? dark : light;
}

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
