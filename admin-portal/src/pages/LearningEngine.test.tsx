import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import LearningEngine from './LearningEngine';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminLearningApi } from '../api/endpoints';

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
vi.mock('../api/endpoints', () => ({
  adminLearningApi: { platformStats: vi.fn() },
}));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <LearningEngine />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

// AdminLayout always renders Sidebar regardless of this page's own permission gate, and Sidebar
// reads `permissions` off the same useAdminAuth() hook -- every mock below must supply it (see
// the bug fix on MerchantIntelligence.test.tsx, which used to omit it and crash on render).
function mockAuth(permissions: string[]) {
  vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({
    hasPermission: (p: string) => permissions.includes(p),
    permissions,
    fullName: 'Support Admin',
    logout: vi.fn(),
  }));
}

describe('LearningEngine', () => {
  beforeEach(() => {
    vi.mocked(useAdminAuth).mockReset();
    vi.mocked(adminLearningApi.platformStats).mockReset();
  });

  it('shows an access-denied message when the account lacks MERCHANT_MANAGE', () => {
    mockAuth([]);
    vi.mocked(adminLearningApi.platformStats).mockResolvedValue({
      learnedMerchantPairs: 0, totalConfirmations: 0, correctedCount: 0, resetCount: 0, trend: [],
    });

    renderPage();

    expect(screen.getByText("You don't have access to this section")).toBeInTheDocument();
  });

  it('renders the platform stats and monthly trend for an account with MERCHANT_MANAGE', async () => {
    mockAuth(['MERCHANT_MANAGE']);
    vi.mocked(adminLearningApi.platformStats).mockResolvedValue({
      learnedMerchantPairs: 120, totalConfirmations: 300, correctedCount: 40, resetCount: 5,
      trend: [{ month: '2026-06', learnedCount: 10, correctedCount: 2 }],
    });

    renderPage();

    await waitFor(() => expect(screen.getByText('120')).toBeInTheDocument());
    expect(screen.getByText('300')).toBeInTheDocument();
    expect(screen.getByText('40')).toBeInTheDocument();
    expect(screen.getByText('5')).toBeInTheDocument();
    expect(screen.getByText('2026-06')).toBeInTheDocument();
  });

  it('shows the empty message when the platform has no learning trend yet', async () => {
    mockAuth(['MERCHANT_MANAGE']);
    vi.mocked(adminLearningApi.platformStats).mockResolvedValue({
      learnedMerchantPairs: 0, totalConfirmations: 0, correctedCount: 0, resetCount: 0, trend: [],
    });

    renderPage();

    await waitFor(() => expect(screen.getByText('No learning activity recorded on the platform yet.')).toBeInTheDocument());
  });
});
