import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { useColorScheme } from 'react-native';
import { userApi } from '../api/endpoints';
import { safeStorage } from '../lib/safeStorage';
import { dark, light, type Palette } from '../theme/palette';

/**
 * Ported from frontend/src/context/ThemeContext.tsx. Same three settings and the same
 * "apply locally now, persist best-effort" model.
 *
 * Two real differences from web:
 *
 * - No DOM class to toggle and no useLayoutEffect. React Native components read the resolved
 *   palette through useTheme() and re-render, so there is no flash-of-wrong-theme to defend
 *   against and nothing to paint before.
 * - The OS preference arrives through useColorScheme(), which is already reactive, replacing the
 *   web's matchMedia listener entirely.
 *
 * Storage is SecureStore-backed and therefore async (see safeStorage), so the stored setting is
 * read in an effect rather than a useState initializer. Until it lands, the app follows the system
 * -- which is the default anyway, so the common case shows no transition at all.
 */
export type ThemeSetting = 'light' | 'dark' | 'system';

export const THEME_SETTINGS: ThemeSetting[] = ['system', 'light', 'dark'];

interface ThemeState {
  /** What the user chose -- may literally be "system", unlike `resolved`. */
  setting: ThemeSetting;
  /** What's actually painted right now, with "system" resolved. */
  resolved: 'light' | 'dark';
  palette: Palette;
  setSetting: (next: ThemeSetting) => void;
}

const STORAGE_KEY = 'finora_theme';

const ThemeContext = createContext<ThemeState | null>(null);

/**
 * The backend's User.theme column predates this feature and defaulted to "ledger" (see the V9
 * migration), so anything that isn't one of the three real settings falls back to "system" rather
 * than rendering neither palette.
 */
function normalize(value: string | null | undefined): ThemeSetting {
  return value === 'light' || value === 'dark' || value === 'system' ? value : 'system';
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  const systemScheme = useColorScheme();
  const [setting, setSettingState] = useState<ThemeSetting>('system');

  // Local preference first, then the account's, so a device that has been used before doesn't
  // flicker through the wrong theme while /users/me is in flight. The remote value wins because it
  // is what follows the user across devices -- matching the web provider's precedence.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      const stored = await safeStorage.getItem(STORAGE_KEY);
      if (cancelled) return;
      if (stored) setSettingState(normalize(stored));

      // Only when signed in: /users/me 401s otherwise, and this provider also wraps the auth stack.
      const token = await safeStorage.getItem('finora_token');
      if (cancelled || !token) return;
      try {
        const user = await userApi.get();
        if (cancelled) return;
        const remote = normalize(user.theme);
        setSettingState(remote);
        void safeStorage.setItem(STORAGE_KEY, remote);
      } catch {
        // Keeps whatever was stored locally -- a theme is not worth surfacing an error for.
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  function setSetting(next: ThemeSetting) {
    setSettingState(next);
    void safeStorage.setItem(STORAGE_KEY, next);
    // Best-effort remote persistence, same pattern as AuthContext.logout(): the visible change
    // applies immediately and is never rolled back by a failed network call.
    void safeStorage.getItem('finora_token').then((token) => {
      if (token) userApi.update({ theme: next }).catch(() => {});
    });
  }

  const resolved: 'light' | 'dark' =
    setting === 'system' ? (systemScheme === 'dark' ? 'dark' : 'light') : setting;

  return (
    <ThemeContext.Provider
      value={{ setting, resolved, palette: resolved === 'dark' ? dark : light, setSetting }}
    >
      {children}
    </ThemeContext.Provider>
  );
}

/**
 * The chosen setting and the setter -- for the one screen that offers the control.
 * Everything else wants useTheme() (see ../theme), which is just the resolved palette.
 */
export function useThemeSetting(): ThemeState {
  const ctx = useContext(ThemeContext);
  if (!ctx) throw new Error('useThemeSetting must be used within ThemeProvider');
  return ctx;
}

/**
 * The resolved palette. Unchanged signature from before the manual override existed, so no screen
 * had to be touched when this became context-driven.
 *
 * Falls back to the system scheme when no provider is mounted rather than throwing. That is not
 * laziness about the error case: a palette has a correct answer without any user preference, and
 * the alternative is that every screen test and any future isolated render has to wrap a provider
 * to display a color. useThemeSetting() above DOES throw, because a setter with no provider behind
 * it silently does nothing, which is a real bug worth surfacing.
 */
export function useTheme(): Palette {
  const ctx = useContext(ThemeContext);
  const systemScheme = useColorScheme();
  if (ctx) return ctx.palette;
  return systemScheme === 'dark' ? dark : light;
}
