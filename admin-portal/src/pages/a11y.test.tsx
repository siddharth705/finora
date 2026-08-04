import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render } from '@testing-library/react';
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

function renderPage(ui: React.ReactElement) {
  vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState());
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>
  );
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
    const { container } = renderPage(page());

    const violations = await axeViolations(container);
    if (violations.length > 0) {
      console.log(`\naxe — ${name}:\n  ${summarise(violations).join('\n  ')}\n`);
    }

    expect(summarise(violations)).toEqual([]);
  }, 20_000);
});
