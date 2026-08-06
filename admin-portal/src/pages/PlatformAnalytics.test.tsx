import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import PlatformAnalytics from './PlatformAnalytics';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminPlatformAnalyticsApi } from '../api/endpoints';

vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
vi.mock('../api/endpoints', () => ({
  adminPlatformAnalyticsApi: { get: vi.fn() },
}));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <PlatformAnalytics />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

// See LearningEngine.test.tsx's mockAuth comment -- AdminLayout always renders Sidebar, which
// reads `permissions` off this same hook, so every mock here must supply it.
function mockAuth(permissions: string[]) {
  vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({
    hasPermission: (p: string) => permissions.includes(p),
    permissions,
    fullName: 'Support Admin',
    logout: vi.fn(),
  }));
}

describe('PlatformAnalytics', () => {
  beforeEach(() => {
    vi.mocked(useAdminAuth).mockReset();
    vi.mocked(adminPlatformAnalyticsApi.get).mockReset();
  });

  it('shows an access-denied message when the account lacks PLATFORM_ANALYTICS_VIEW', () => {
    mockAuth([]);
    vi.mocked(adminPlatformAnalyticsApi.get).mockResolvedValue({ topCategories: [], topMerchants: [] });

    renderPage();

    expect(screen.getByText("You don't have access to this section")).toBeInTheDocument();
  });

  it('renders top categories and top merchants for an account with PLATFORM_ANALYTICS_VIEW', async () => {
    mockAuth(['PLATFORM_ANALYTICS_VIEW']);
    vi.mocked(adminPlatformAnalyticsApi.get).mockResolvedValue({
      topCategories: [{ categoryName: 'Groceries', totalSpend: 250, transactionCount: 12 }],
      topMerchants: [{ merchantName: 'Amazon', totalSpend: 75.5, transactionCount: 3 }],
    });

    renderPage();

    await waitFor(() => expect(screen.getByText('Groceries')).toBeInTheDocument());
    // formatCurrency always renders exactly two decimal places, so a whole-number spend still
    // shows "250.00", not "250" -- and now carries the rupee symbol and an explicitly pinned
    // 'en-IN' locale, like every other money formatter in both apps. It used to omit the symbol
    // and pass undefined as the locale, so an unlabelled figure sat next to an unlabelled
    // transaction count and grouped according to the visiting admin's own OS locale.
    expect(screen.getByText('₹250.00')).toBeInTheDocument();
    expect(screen.getByText('12')).toBeInTheDocument();
    expect(screen.getByText('Amazon')).toBeInTheDocument();
    expect(screen.getByText('₹75.50')).toBeInTheDocument();
    expect(screen.getByText('3')).toBeInTheDocument();
  });

  it('shows the empty messages when the platform has no spend recorded yet', async () => {
    mockAuth(['PLATFORM_ANALYTICS_VIEW']);
    vi.mocked(adminPlatformAnalyticsApi.get).mockResolvedValue({ topCategories: [], topMerchants: [] });

    renderPage();

    await waitFor(() => expect(screen.getByText('No categorized spend recorded on the platform yet.')).toBeInTheDocument());
    expect(screen.getByText('No merchant spend recorded on the platform yet.')).toBeInTheDocument();
  });
});
