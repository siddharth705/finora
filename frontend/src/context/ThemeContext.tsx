import { createContext, useContext, useEffect, useLayoutEffect, useState, type ReactNode } from 'react';
import { userApi } from '../api/endpoints';

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
  const [theme, setThemeState] = useState<ThemeSetting>(() => normalize(localStorage.getItem(STORAGE_KEY)));
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

  // Pull the account's saved preference once so the choice follows the user across devices —
  // only when actually signed in, since /users/me would otherwise 401 on the public pages
  // (Landing/Login/Register) that also mount this provider.
  useEffect(() => {
    if (!localStorage.getItem('finora_token')) return;
    userApi
      .get()
      .then((u) => {
        const remote = normalize(u.theme);
        setThemeState(remote);
        localStorage.setItem(STORAGE_KEY, remote);
      })
      .catch(() => {});
  }, []);

  function setTheme(next: ThemeSetting) {
    setThemeState(next);
    localStorage.setItem(STORAGE_KEY, next);
    // Best-effort remote persistence, same pattern as AuthContext.logout(): the local UI change
    // applies immediately and never waits on (or gets rolled back by) the network call.
    if (localStorage.getItem('finora_token')) {
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
