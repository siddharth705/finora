/**
 * The one entry point for theming. Screens import everything they need from `../theme`; the split
 * behind it (palette values in ./palette, the provider and hooks in ../context/ThemeContext) is an
 * implementation detail that exists only to keep those two from importing each other in a cycle.
 */
export { radius, spacing, type Palette } from './palette';
export { fonts, useAppFonts } from './fonts';
export {
  ThemeProvider,
  useTheme,
  useThemeSetting,
  THEME_SETTINGS,
  type ThemeSetting,
} from '../context/ThemeContext';
