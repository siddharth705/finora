import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider, useTheme, AUTH_CHANGED_EVENT } from './ThemeContext';
import { userApi } from '../api/endpoints';

// ThemeProvider's third useEffect calls userApi.get() whenever a token is present, to pull the
// account's saved theme from the server -- mocked here so tests that don't set a token never hit
// it, and the one test that does can control exactly what it resolves with.
vi.mock('../api/endpoints', () => ({
  userApi: {
    get: vi.fn().mockResolvedValue({ theme: 'dark' }),
    update: vi.fn().mockResolvedValue({}),
  },
}));

function Probe() {
  const { theme, resolvedTheme, setTheme } = useTheme();
  return (
    <div>
      <span data-testid="theme">{theme}</span>
      <span data-testid="resolved">{resolvedTheme}</span>
      <button onClick={() => setTheme('dark')}>Set dark</button>
    </div>
  );
}

describe('ThemeContext', () => {
  beforeEach(() => {
    localStorage.clear();
    document.documentElement.classList.remove('dark');
  });

  it('falls back to "system" when nothing is stored, and resolves it to light when the OS has no dark preference', () => {
    render(
      <ThemeProvider>
        <Probe />
      </ThemeProvider>
    );

    expect(screen.getByTestId('theme')).toHaveTextContent('system');
    expect(screen.getByTestId('resolved')).toHaveTextContent('light');
  });

  it('normalizes an unrecognized stored value (e.g. the legacy "ledger" default) back to "system"', () => {
    localStorage.setItem('finora_theme', 'ledger');

    render(
      <ThemeProvider>
        <Probe />
      </ThemeProvider>
    );

    expect(screen.getByTestId('theme')).toHaveTextContent('system');
  });

  it('setTheme updates both the resolved theme and localStorage, and toggles the <html> "dark" class', async () => {
    const user = userEvent.setup();
    render(
      <ThemeProvider>
        <Probe />
      </ThemeProvider>
    );

    await user.click(screen.getByText('Set dark'));

    expect(screen.getByTestId('theme')).toHaveTextContent('dark');
    expect(screen.getByTestId('resolved')).toHaveTextContent('dark');
    expect(localStorage.getItem('finora_theme')).toBe('dark');
    await waitFor(() => expect(document.documentElement.classList.contains('dark')).toBe(true));
  });

  /**
   * Bug fix regression test: ThemeProvider wraps AuthProvider (App.tsx), so its "pull the
   * account's saved theme" effect used to run exactly once on mount, using whatever
   * localStorage.getItem('finora_token') was at that instant. A user landing on a public page
   * (no token yet) and then logging in within the same SPA session never got a second chance to
   * sync -- the theme stayed on whatever it was pre-login until a full page reload. AuthContext
   * now dispatches AUTH_CHANGED_EVENT after login/register specifically so this effect gets a
   * second chance without needing a circular useAuth() dependency.
   */
  it('re-pulls the saved theme when AUTH_CHANGED_EVENT fires after a token appears mid-session', async () => {
    render(
      <ThemeProvider>
        <Probe />
      </ThemeProvider>
    );

    // No token yet -- confirms the initial mount correctly skipped the sync (same as the
    // "falls back to system" test above), so the event below is what actually triggers it.
    expect(screen.getByTestId('theme')).toHaveTextContent('system');
    expect(userApi.get).not.toHaveBeenCalled();

    localStorage.setItem('finora_token', 'a-real-token');
    window.dispatchEvent(new Event(AUTH_CHANGED_EVENT));

    await waitFor(() => expect(screen.getByTestId('theme')).toHaveTextContent('dark'));
    expect(localStorage.getItem('finora_theme')).toBe('dark');
  });
});
