import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import ReconciliationMonitor from './ReconciliationMonitor';
import { useAdminAuth } from '../context/AdminAuthContext';
import { adminReconciliationApi } from '../api/endpoints';

vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
vi.mock('../api/endpoints', () => ({
  adminReconciliationApi: { platformStats: vi.fn() },
}));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <ReconciliationMonitor />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

// See LearningEngine.test.tsx's mockAuth comment -- AdminLayout always renders Sidebar, which
// reads `permissions` off this same hook, so every mock here must supply it.
function mockAuth(permissions: string[]) {
  vi.mocked(useAdminAuth).mockReturnValue({
    hasPermission: (p: string) => permissions.includes(p),
    permissions,
    fullName: 'Support Admin',
    logout: vi.fn(),
  } as ReturnType<typeof useAdminAuth>);
}

describe('ReconciliationMonitor', () => {
  beforeEach(() => {
    vi.mocked(useAdminAuth).mockReset();
    vi.mocked(adminReconciliationApi.platformStats).mockReset();
  });

  it('shows an access-denied message when the account lacks RECONCILIATION_VIEW', () => {
    mockAuth([]);
    vi.mocked(adminReconciliationApi.platformStats).mockResolvedValue({
      okCount: 0, duplicateCount: 0, transferCount: 0, refundCount: 0, recurringCount: 0, totalTransactions: 0,
    });

    renderPage();

    expect(screen.getByText("You don't have access to this section")).toBeInTheDocument();
  });

  it('renders the platform reconciliation breakdown for an account with RECONCILIATION_VIEW', async () => {
    mockAuth(['RECONCILIATION_VIEW']);
    vi.mocked(adminReconciliationApi.platformStats).mockResolvedValue({
      okCount: 900, duplicateCount: 12, transferCount: 34, refundCount: 8, recurringCount: 56, totalTransactions: 954,
    });

    renderPage();

    await waitFor(() => expect(screen.getByText('954')).toBeInTheDocument());
    expect(screen.getByText('900')).toBeInTheDocument();
    expect(screen.getByText('12')).toBeInTheDocument();
    expect(screen.getByText('34')).toBeInTheDocument();
    expect(screen.getByText('8')).toBeInTheDocument();
    expect(screen.getByText('56')).toBeInTheDocument();
  });
});
