import { useFonts } from 'expo-font';
import {
  Inter_400Regular,
  Inter_500Medium,
  Inter_600SemiBold,
  Inter_700Bold,
  Inter_800ExtraBold,
} from '@expo-google-fonts/inter';
import { Manrope_600SemiBold, Manrope_700Bold, Manrope_800ExtraBold } from '@expo-google-fonts/manrope';

/**
 * Same pair and weight subset the web app already loads (frontend/index.html's Google Fonts URL:
 * Inter 400-800, Manrope 600-800) -- Manrope for headings, Inter for everything else. Not a new
 * font choice, just extending an existing one: the mobile app has never loaded a custom font
 * before this, so every screen has been rendering in the OS default.
 *
 * `fontWeight` should NOT be set alongside these -- each name below IS a specific weight's font
 * file, and RN will still try to synthetically bold/thin on top of it if `fontWeight` disagrees,
 * which looks wrong on a real font in a way it never did on the system font.
 */
export const fonts = {
  display: 'Manrope_800ExtraBold',
  displayBold: 'Manrope_700Bold',
  displaySemibold: 'Manrope_600SemiBold',
  body: 'Inter_400Regular',
  bodyMedium: 'Inter_500Medium',
  bodySemibold: 'Inter_600SemiBold',
  bodyBold: 'Inter_700Bold',
  bodyExtraBold: 'Inter_800ExtraBold',
} as const;

/** Called once, from App.tsx, before anything renders -- see its own comment on why. */
export function useAppFonts() {
  return useFonts({
    Inter_400Regular,
    Inter_500Medium,
    Inter_600SemiBold,
    Inter_700Bold,
    Inter_800ExtraBold,
    Manrope_600SemiBold,
    Manrope_700Bold,
    Manrope_800ExtraBold,
  });
}
