import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthProvider } from '../context/AuthContext';
import { ThemeProvider } from '../context/ThemeContext';
import { axeViolations, summarise } from '../test/a11y.measure';
import Landing from './Landing';
import AuthEntry from './AuthEntry';
import { PasswordStep } from './auth-entry/PasswordStep';
import { RegisterStep } from './auth-entry/RegisterStep';
import ForgotPassword from './ForgotPassword';

/**
 * The accessibility baseline for this app — a measurement, not a fix.
 *
 * Item 12 of the repo improvement proposal said outright that no accessibility audit had been
 * performed and that attribute counts (mobile 51 across 46 files vs this app's 21 across 63) are a
 * proxy for effort, not conformance. So the first deliverable is a number.
 *
 * Scoped to the four pages an unauthenticated visitor can actually reach. They are the entire
 * public surface, they need no auth fixtures, and they are where an accessibility problem is most
 * likely to stop someone becoming a user at all. The authenticated pages are not covered yet; that
 * is a known gap, not an oversight.
 *
 * READ THE LIMIT IN a11y.measure.ts BEFORE TRUSTING A PASS. jsdom has no layout engine, so
 * colour-contrast and other geometry-dependent rules cannot run. Green here means "no violations
 * detectable without a browser".
 */

vi.mock('../api/endpoints', () => ({
  authApi: { login: vi.fn(), register: vi.fn(), logout: vi.fn(), refresh: vi.fn(), forgotPassword: vi.fn() },
  userApi: { get: vi.fn(), update: vi.fn() },
}));

function renderPage(ui: React.ReactElement) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <ThemeProvider>
          <AuthProvider>{ui}</AuthProvider>
        </ThemeProvider>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

// AuthEntry replaces the old Login/Register page-level checks (D-26 unified entry, 2026-08-24) --
// it only ever renders the identify step on a fresh mount, so PasswordStep/RegisterStep (the
// steps AuthEntry switches into) are checked directly below for equivalent, actually-narrower
// coverage than the old two-page setup gave.
const PUBLIC_PAGES: ReadonlyArray<readonly [string, () => React.ReactElement]> = [
  ['Landing', () => <Landing />],
  ['AuthEntry (identify step)', () => <AuthEntry />],
  ['PasswordStep', () => <PasswordStep identifier="jane@example.com" banner={null} onSuccess={() => {}} onNotYou={() => {}} />],
  ['RegisterStep', () => <RegisterStep prefill={{}} referralCode={undefined} onSuccess={() => {}} onAccountExists={() => {}} />],
  ['ForgotPassword', () => <ForgotPassword />],
];

beforeEach(() => {
  vi.clearAllMocks();
});

describe('accessibility baseline — public pages', () => {
  it.each(PUBLIC_PAGES)('%s has no machine-detectable violations', async (name, page) => {
    const { container } = renderPage(page());

    const violations = await axeViolations(container);
    if (violations.length > 0) {
      // Printed as well as asserted: the assertion message alone truncates, and the point of this
      // suite is to hand over a list that can be triaged.
      console.log(`\naxe — ${name}:\n  ${summarise(violations).join('\n  ')}\n`);
    }

    expect(summarise(violations)).toEqual([]);
  }, 20_000);
});
