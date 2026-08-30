import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Referrals from './Referrals';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminReferralsApi } from '../api/endpoints';
import type { AdminReferralSummaryDto } from '../types';

// AdminLayout now renders ThemeToggle (dark-mode support), which calls useTheme() --
// same reason adminSearchApi is stubbed below for GlobalSearch: a real ThemeProvider isn't
// mounted in these tests, so without this mock every AdminLayout-wrapped page throws before
// any assertion runs.
vi.mock('../context/ThemeContext', () => ({
  useTheme: () => ({ theme: 'system', resolvedTheme: 'light', setTheme: vi.fn() }),
}));
vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
const notifySuccess = vi.fn();
const notifyError = vi.fn();
vi.mock('../context/NotificationContext', () => ({
  useNotify: () => ({ success: notifySuccess, error: notifyError }),
}));
vi.mock('../api/endpoints', () => ({
  adminReferralsApi: { list: vi.fn(), creditReward: vi.fn() },
}));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <Referrals />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

function mockAuth(permissions: string[]) {
  vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({
    hasPermission: (p: string) => permissions.includes(p),
    permissions,
    fullName: 'Ops Admin',
    logout: vi.fn(),
  }));
}

function referral(overrides: Partial<AdminReferralSummaryDto> = {}): AdminReferralSummaryDto {
  return {
    referralId: 'referral-1',
    referrerUserId: 'user-1', referrerEmail: 'referrer@example.com', referrerFullName: 'Referrer Person',
    referredUserId: 'user-2', referredEmail: 'referred@example.com', referredFullName: 'Referred Person',
    status: 'REGISTERED', reward: null, createdAt: '2026-08-01T00:00:00Z',
    ...overrides,
  };
}

function pageOf(...rows: AdminReferralSummaryDto[]) {
  return { content: rows, page: 0, size: 20, totalElements: rows.length, totalPages: 1 };
}

describe('Referrals (admin)', () => {
  beforeEach(() => {
    vi.mocked(useAdminAuth).mockReset();
    vi.mocked(adminReferralsApi.list).mockReset();
    vi.mocked(adminReferralsApi.creditReward).mockReset();
  });

  it('shows an access-denied message when the account lacks REFERRAL_MANAGEMENT_VIEW', () => {
    mockAuth([]);
    vi.mocked(adminReferralsApi.list).mockResolvedValue(pageOf());

    renderPage();

    expect(screen.getByText("You don't have access to this section")).toBeInTheDocument();
  });

  it('renders every referral with both parties and its status', async () => {
    mockAuth(['REFERRAL_MANAGEMENT_VIEW']);
    vi.mocked(adminReferralsApi.list).mockResolvedValue(pageOf(referral()));

    renderPage();

    await waitFor(() => expect(screen.getByText('Referrer Person')).toBeInTheDocument());
    expect(screen.getByText('Referred Person')).toBeInTheDocument();
    expect(screen.getByText('REGISTERED')).toBeInTheDocument();
  });

  it('shows no credit action for a referral that has not reached SUBSCRIBED', async () => {
    mockAuth(['REFERRAL_MANAGEMENT_VIEW']);
    vi.mocked(adminReferralsApi.list).mockResolvedValue(pageOf(referral({ status: 'REGISTERED' })));

    renderPage();

    await waitFor(() => expect(screen.getByText('Referrer Person')).toBeInTheDocument());
    expect(screen.queryByRole('button', { name: /credit/i })).not.toBeInTheDocument();
  });

  it('credits a reward for a SUBSCRIBED referral and refetches the list', async () => {
    const user = userEvent.setup();
    mockAuth(['REFERRAL_MANAGEMENT_VIEW']);
    vi.mocked(adminReferralsApi.list).mockResolvedValue(pageOf(referral({ status: 'SUBSCRIBED' })));
    vi.mocked(adminReferralsApi.creditReward).mockResolvedValue({} as any);

    renderPage();
    await waitFor(() => expect(screen.getByText('Referrer Person')).toBeInTheDocument());

    await user.type(screen.getByPlaceholderText('Amount'), '250');
    await user.click(screen.getByRole('button', { name: /credit/i }));

    await waitFor(() => expect(adminReferralsApi.creditReward).toHaveBeenCalledWith(
      'referral-1', 250, 'Admin credited referral reward'
    ));
    await waitFor(() => expect(adminReferralsApi.list).toHaveBeenCalledTimes(2));
  });

  it('shows the rewarded amount, not a credit action, once a referral is REWARDED', async () => {
    mockAuth(['REFERRAL_MANAGEMENT_VIEW']);
    vi.mocked(adminReferralsApi.list).mockResolvedValue(pageOf(referral({ status: 'REWARDED', reward: 250 })));

    renderPage();

    await waitFor(() => expect(screen.getByText('₹250')).toBeInTheDocument());
    expect(screen.queryByRole('button', { name: /credit/i })).not.toBeInTheDocument();
  });

  /** Referral volume grows with the user base (ReferralService.listAll's own doc comment) --
   *  the whole reason this page was moved off a fetch-all list. Proves the page state actually
   *  drives the next request, not just that Pagination renders. */
  it('requests the next page of referrals when Pagination is clicked', async () => {
    mockAuth(['REFERRAL_MANAGEMENT_VIEW']);
    vi.mocked(adminReferralsApi.list).mockResolvedValue(
      { content: [referral()], page: 0, size: 20, totalElements: 25, totalPages: 2 }
    );

    renderPage();
    await waitFor(() => expect(screen.getByText('Referrer Person')).toBeInTheDocument());
    expect(screen.getByText('Page 1 of 2')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Next page' }));

    await waitFor(() => expect(adminReferralsApi.list).toHaveBeenCalledWith(1, 20));
  });
});
