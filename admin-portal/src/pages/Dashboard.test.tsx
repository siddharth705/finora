import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Dashboard from './Dashboard';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminDashboardApi, adminStatsApi, adminSystemApi } from '../api/endpoints';
import type { OperationalDashboardDto } from '../types';

/**
 * D-27 PR3-D. Dashboard.tsx had no prior test file -- this covers only what this change added
 * (the Activation Funnel section), not the whole existing page (health banner, needs attention,
 * system status), matching frontend's own Dashboard.test.tsx's stated scoping discipline.
 */
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
  adminDashboardApi: { overview: vi.fn(), activationFunnel: vi.fn() },
  adminStatsApi: { overview: vi.fn() },
  adminSystemApi: { health: vi.fn() },
}));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <Dashboard />
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

function overview(): OperationalDashboardDto {
  return {
    totalUsers: 1240,
    activeUsersToday: 80,
    transactionsToday: 300,
    importsToday: 12,
    importsWithSkippedRowsToday: 0,
    needsAttention: { importsWithSkippedRowsToday: 0, lockedAccounts: 0, transactionsNeedingCategoryReview: 0, transactionsFlaggedAsDuplicates: 0 },
    health: { overallStatus: 'UP', providers: [] },
    alerts: [],
    recentActivity: [],
  };
}

describe('Dashboard — Activation Funnel', () => {
  beforeEach(() => {
    vi.mocked(useAdminAuth).mockReset();
    vi.mocked(adminDashboardApi.overview).mockReset().mockResolvedValue(overview());
    vi.mocked(adminDashboardApi.activationFunnel).mockReset();
    vi.mocked(adminStatsApi.overview).mockReset().mockResolvedValue({
      totalAccounts: 0, newUsersLast7Days: 0, totalStatementImports: 0, suspendedUsers: 0,
    } as any);
    vi.mocked(adminSystemApi.health).mockReset();
    mockAuth(['PLATFORM_STATS_VIEW']);
  });

  it('renders each stage with its own count and percentage of signups', async () => {
    vi.mocked(adminDashboardApi.activationFunnel).mockResolvedValue({
      signedUp: 1240, firstImport: 890, firstBudget: 410, firstGoal: 260,
    });

    renderPage();

    expect(await screen.findByText('Activation funnel')).toBeInTheDocument();
    expect(screen.getByText('Signed up')).toBeInTheDocument();
    expect(screen.getByText('First import')).toBeInTheDocument();
    expect(screen.getByText('First budget')).toBeInTheDocument();
    expect(screen.getByText('First goal')).toBeInTheDocument();

    // 890 / 1240 = 71.77% -> rounds to 72%, matching the owner's own approved mockup.
    expect(screen.getByText('890')).toBeInTheDocument();
    expect(screen.getByText('(72%)')).toBeInTheDocument();
    expect(screen.getByText('(100%)')).toBeInTheDocument(); // Signed up is always the 100% base
    expect(screen.getByText('(33%)')).toBeInTheDocument(); // 410/1240
    expect(screen.getByText('(21%)')).toBeInTheDocument(); // 260/1240
  });

  it('does not render the section at all until the funnel query resolves', async () => {
    vi.mocked(adminDashboardApi.activationFunnel).mockReturnValue(new Promise(() => {})); // never resolves

    renderPage();

    await waitFor(() => expect(screen.getByText('Operational Dashboard')).toBeInTheDocument());
    expect(screen.queryByText('Activation funnel')).not.toBeInTheDocument();
  });

  it('shows a 0% bar rather than dividing by zero on a platform with no signups yet', async () => {
    vi.mocked(adminDashboardApi.activationFunnel).mockResolvedValue({
      signedUp: 0, firstImport: 0, firstBudget: 0, firstGoal: 0,
    });

    renderPage();

    expect(await screen.findByText('Activation funnel')).toBeInTheDocument();
    // Every stage shows 0 (0%), never NaN% -- the section's own divide-by-zero guard.
    expect(screen.getAllByText('(0%)').length).toBe(4);
  });
});
