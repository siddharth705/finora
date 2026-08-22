import { createContext, useContext, useEffect, useLayoutEffect, useState, type ReactNode } from 'react';
import { safeStorage } from '../lib/safeStorage';

export type ThemeSetting = 'light' | 'dark' | 'system';

interface ThemeState {
  /** The admin's chosen setting — may literally be "system", unlike resolvedTheme. */
  theme: ThemeSetting;
  /** What's actually painted right now (system resolved to light/dark). Use this for icons. */
  resolvedTheme: 'light' | 'dark';
  setTheme: (next: ThemeSetting) => void;
}

const ThemeContext = createContext<ThemeState | null>(null);
const STORAGE_KEY = 'finora_admin_theme';

function normalize(value: string | null | undefined): ThemeSetting {
  return value === 'light' || value === 'dark' || value === 'system' ? value : 'system';
}

function systemPrefersDark(): boolean {
  return typeof window !== 'undefined' && window.matchMedia('(prefers-color-scheme: dark)').matches;
}

// Simpler than the user app's ThemeContext (finora/frontend/src/context/ThemeContext.tsx) --
// admin accounts have no `theme` column or /users/me-equivalent endpoint to sync against, so this
// stays local-only (localStorage, this browser) rather than following an admin across devices.
export function ThemeProvider({ children }: { children: ReactNode }) {
  const [theme, setThemeState] = useState<ThemeSetting>(() => normalize(safeStorage.getItem(STORAGE_KEY)));
  const [systemDark, setSystemDark] = useState(systemPrefersDark);

  const resolvedTheme: 'light' | 'dark' = theme === 'system' ? (systemDark ? 'dark' : 'light') : theme;

  // useLayoutEffect (not useEffect) so the class lands before the browser paints -- avoids a
  // flash of the wrong theme when the resolved value differs from whatever classList started with.
  useLayoutEffect(() => {
    document.documentElement.classList.toggle('dark', resolvedTheme === 'dark');
  }, [resolvedTheme]);

  // Live-track OS preference changes while set to "system" without requiring a page reload.
  useEffect(() => {
    const mql = window.matchMedia('(prefers-color-scheme: dark)');
    const handler = (e: MediaQueryListEvent) => setSystemDark(e.matches);
    mql.addEventListener('change', handler);
    return () => mql.removeEventListener('change', handler);
  }, []);

  function setTheme(next: ThemeSetting) {
    setThemeState(next);
    safeStorage.setItem(STORAGE_KEY, next);
  }

  return <ThemeContext.Provider value={{ theme, resolvedTheme, setTheme }}>{children}</ThemeContext.Provider>;
}

export function useTheme() {
  const ctx = useContext(ThemeContext);
  if (!ctx) throw new Error('useTheme must be used within ThemeProvider');
  return ctx;
}
