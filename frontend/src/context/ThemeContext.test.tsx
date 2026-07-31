import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider, useTheme } from './ThemeContext';

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
});
