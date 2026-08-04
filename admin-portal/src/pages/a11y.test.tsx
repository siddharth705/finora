import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { axeViolations, summarise } from '../test/a11y.measure';
import Login from './Login';
import ForgotPassword from './ForgotPassword';
import ResetPassword from './ResetPassword';

/**
 * Accessibility baseline for the admin portal — a measurement, not a fix. Companion to the user
 * app's src/pages/a11y.test.tsx; see that file and a11y.measure.ts for why axe rather than
 * eslint-plugin-jsx-a11y (no release of that plugin supports ESLint 10), and for the jsdom limit
 * that means a pass is "nothing detectable without a browser", not "accessible".
 *
 * Scoped to the pages reachable without a session. The authenticated pages are a known gap.
 *
 * Bug fix: renderPage used mockAdminAuthState() with no override, whose default `token` is the
 * truthy 'test-token' (a sane default for every OTHER test file, which renders authenticated
 * pages). Login.tsx is the one PUBLIC_PAGES entry that calls useAdminAuth() -- its very first
 * render sees that truthy token and returns `<Navigate to="/" replace />` before the sign-in form
 * ever mounts, same as it would for a real signed-in visitor. Login's own axe check was therefore
 * scanning an empty container (Navigate renders nothing here; there is no matching <Routes> for it
 * to redirect into): "no machine-detectable violations" was trivially true because there was
 * nothing to detect, not because the sign-in form is accessible. token: null makes the mock match
 * what "reachable without a session" actually means, and the added waitFor lets Login's own
 * `checkingSetup` loading state (a real `setupApi.status()` call resolves after mount) settle into
 * the real form before axe runs -- without it, this would still race and sometimes scan the
 * "Loading…" placeholder instead.
 */

vi.mock('../context/AdminAuthContext', () => ({ useAdminAuth: vi.fn() }));
vi.mock('../context/NotificationContext', () => ({
  useNotify: () => ({ success: vi.fn(), error: vi.fn() }),
}));
vi.mock('../api/endpoints', () => {
  const pending = () => new Promise(() => {});
  return {
    authApi: { login: vi.fn(), logout: vi.fn() },
    setupApi: { status: vi.fn(async () => ({ setupRequired: false })) },
    meApi: { access: vi.fn(pending) },
    adminSearchApi: { search: vi.fn(pending) },
  };
});

async function renderPage(ui: React.ReactElement) {
  // token: null -- these are exactly the pages reachable WITHOUT a session; a truthy token (the
  // mock's sane default for authenticated-page tests elsewhere) would make Login.tsx redirect away
  // before its form ever renders. See this file's own doc comment for the bug this was hiding.
  vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({ token: null }));
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const { container } = render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>
  );
  // Every page in PUBLIC_PAGES settles into a real <form> -- Login only after its own
  // setupApi.status() check resolves and checkingSetup flips false. Scanning before that would
  // sometimes catch Login's transient "Loading…" placeholder instead of the real sign-in form.
  await waitFor(() => expect(container.querySelector('form')).toBeTruthy());
  return container;
}

const PUBLIC_PAGES: ReadonlyArray<readonly [string, () => React.ReactElement]> = [
  ['Login', () => <Login />],
  ['ForgotPassword', () => <ForgotPassword />],
  ['ResetPassword', () => <ResetPassword />],
];

beforeEach(() => {
  vi.clearAllMocks();
});

describe('accessibility baseline — admin public pages', () => {
  it.each(PUBLIC_PAGES)('%s has no machine-detectable violations', async (name, page) => {
    const container = await renderPage(page());

    const violations = await axeViolations(container);
    if (violations.length > 0) {
      console.log(`\naxe — ${name}:\n  ${summarise(violations).join('\n  ')}\n`);
    }

    expect(summarise(violations)).toEqual([]);
  }, 20_000);
});
