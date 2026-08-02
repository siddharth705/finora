import { createContext, useContext, useEffect, useLayoutEffect, useState, type ReactNode } from 'react';
import { userApi } from '../api/endpoints';
import { safeStorage } from '../lib/safeStorage';

export type ThemeSetting = 'light' | 'dark' | 'system';

interface ThemeState {
  /** The user's chosen setting — may literally be "system", unlike resolvedTheme. */
  theme: ThemeSetting;
  /** What's actually painted right now (system resolved to light/dark). Use this for icons. */
  resolvedTheme: 'light' | 'dark';
  setTheme: (next: ThemeSetting) => void;
}

const ThemeContext = createContext<ThemeState | null>(null);
const STORAGE_KEY = 'finora_theme';
// Dispatched by AuthContext after a successful login/register, since ThemeProvider wraps
// AuthProvider (App.tsx) and so can't consume useAuth()'s reactive token directly.
export const AUTH_CHANGED_EVENT = 'finora:auth-changed';

// The backend's User.theme column predates this feature and defaulted to "ledger" (see V9
// migration) — anything that isn't one of our three real settings falls back to "system"
// rather than crashing or silently rendering neither light nor dark.
function normalize(value: string | null | undefined): ThemeSetting {
  return value === 'light' || value === 'dark' || value === 'system' ? value : 'system';
}

function systemPrefersDark(): boolean {
  return typeof window !== 'undefined' && window.matchMedia('(prefers-color-scheme: dark)').matches;
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [theme, setThemeState] = useState<ThemeSetting>(() => normalize(safeStorage.getItem(STORAGE_KEY)));
  const [systemDark, setSystemDark] = useState(systemPrefersDark);

  const resolvedTheme: 'light' | 'dark' = theme === 'system' ? (systemDark ? 'dark' : 'light') : theme;

  // useLayoutEffect (not useEffect) so the class lands before the browser paints — avoids a
  // flash of the wrong theme when the resolved value differs from whatever classList started with.
  useLayoutEffect(() => {
    document.documentElement.classList.toggle('dark', resolvedTheme === 'dark');
  }, [resolvedTheme]);

  // Live-track OS preference changes while set to "system" (e.g. the OS switches to dark at
  // sunset) without requiring a page reload.
  useEffect(() => {
    const mql = window.matchMedia('(prefers-color-scheme: dark)');
    const handler = (e: MediaQueryListEvent) => setSystemDark(e.matches);
    mql.addEventListener('change', handler);
    return () => mql.removeEventListener('change', handler);
  }, []);

  // Pull the account's saved preference so the choice follows the user across devices — only
  // when actually signed in, since /users/me would otherwise 401 on the public pages
  // (Landing/Login/Register) that also mount this provider. ThemeProvider wraps AuthProvider
  // (see App.tsx), so it can't call useAuth() to react to login/logout directly -- it instead
  // re-runs this sync on the AUTH_CHANGED_EVENT AuthContext dispatches after login/register,
  // not just once on mount. Without this, a theme saved from another device was only ever
  // pulled in if the tab happened to load fresh with a token already present -- logging in
  // during the same SPA session left the theme on whatever it was pre-login until a full
  // page reload.
  useEffect(() => {
    function syncFromServer() {
      if (!safeStorage.getItem('finora_token')) return;
      userApi
        .get()
        .then((u) => {
          const remote = normalize(u.theme);
          setThemeState(remote);
          safeStorage.setItem(STORAGE_KEY, remote);
        })
        .catch(() => {});
    }
    syncFromServer();
    window.addEventListener(AUTH_CHANGED_EVENT, syncFromServer);
    return () => window.removeEventListener(AUTH_CHANGED_EVENT, syncFromServer);
  }, []);

  function setTheme(next: ThemeSetting) {
    setThemeState(next);
    safeStorage.setItem(STORAGE_KEY, next);
    // Best-effort remote persistence, same pattern as AuthContext.logout(): the local UI change
    // applies immediately and never waits on (or gets rolled back by) the network call.
    if (safeStorage.getItem('finora_token')) {
      userApi.update({ theme: next }).catch(() => {});
    }
  }

  return <ThemeContext.Provider value={{ theme, resolvedTheme, setTheme }}>{children}</ThemeContext.Provider>;
}

export function useTheme() {
  const ctx = useContext(ThemeContext);
  if (!ctx) throw new Error('useTheme must be used within ThemeProvider');
  return ctx;
}
