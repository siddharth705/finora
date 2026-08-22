import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Subscriptions from './Subscriptions';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminSubscriptionsApi } from '../api/endpoints';
import type { SubscriptionSummaryDto } from '../types';

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
// Subscriptions.tsx calls useNotify() -- without this mock, rendering outside a real
// NotificationProvider throws (see GlobalRules.test.tsx's identical fix note).
const notifySuccess = vi.fn();
const notifyError = vi.fn();
vi.mock('../context/NotificationContext', () => ({
  useNotify: () => ({ success: notifySuccess, error: notifyError }),
}));
vi.mock('../api/endpoints', () => ({
  adminSubscriptionsApi: { list: vi.fn(), changePlan: vi.fn() },
}));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <Subscriptions />
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

function subscription(overrides: Partial<SubscriptionSummaryDto> = {}): SubscriptionSummaryDto {
  return {
    subscriptionId: 'sub-1', userId: 'user-1', userEmail: 'jane@example.com', userFullName: 'Jane Doe',
    planCode: 'FREE', planName: 'Free', status: 'ACTIVE', startDate: '2026-08-01',
    endDate: null, renewalDate: null, ...overrides,
  };
}

describe('Subscriptions', () => {
  beforeEach(() => {
    vi.mocked(useAdminAuth).mockReset();
    vi.mocked(adminSubscriptionsApi.list).mockReset();
    vi.mocked(adminSubscriptionsApi.changePlan).mockReset();
  });

  it('shows an access-denied message when the account lacks SUBSCRIPTION_MANAGEMENT_VIEW', () => {
    mockAuth([]);
    vi.mocked(adminSubscriptionsApi.list).mockResolvedValue([]);

    renderPage();

    expect(screen.getByText("You don't have access to this section")).toBeInTheDocument();
  });

  it('renders every subscription with the user, plan, and status', async () => {
    mockAuth(['SUBSCRIPTION_MANAGEMENT_VIEW']);
    vi.mocked(adminSubscriptionsApi.list).mockResolvedValue([subscription()]);

    renderPage();

    await waitFor(() => expect(screen.getByText('Jane Doe')).toBeInTheDocument());
    expect(screen.getByText('jane@example.com')).toBeInTheDocument();
    expect(screen.getByText('ACTIVE')).toBeInTheDocument();
  });

  it('changes a plan and refetches the list on success', async () => {
    mockAuth(['SUBSCRIPTION_MANAGEMENT_VIEW']);
    vi.mocked(adminSubscriptionsApi.list).mockResolvedValue([subscription()]);
    vi.mocked(adminSubscriptionsApi.changePlan).mockResolvedValue({} as any);

    renderPage();
    await waitFor(() => expect(screen.getByText('Jane Doe')).toBeInTheDocument());

    const select = screen.getByDisplayValue('FREE') as HTMLSelectElement;
    select.value = 'PLUS';
    select.dispatchEvent(new Event('change', { bubbles: true }));

    await waitFor(() => expect(adminSubscriptionsApi.changePlan).toHaveBeenCalledWith(
      'user-1', 'PLUS', 'Admin manual override'
    ));
    await waitFor(() => expect(adminSubscriptionsApi.list).toHaveBeenCalledTimes(2));
  });
});
