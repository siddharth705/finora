import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import SystemHealth from './SystemHealth';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminSystemApi } from '../api/endpoints';

vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
vi.mock('../api/endpoints', () => ({
  adminSystemApi: { health: vi.fn(), recentImports: vi.fn() },
}));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <SystemHealth />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

// AdminLayout always renders Sidebar, which reads `permissions` off this same hook.
function mockAuth(permissions: string[]) {
  vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({
    hasPermission: (p: string) => permissions.includes(p),
    permissions,
    fullName: 'Support Admin',
    logout: vi.fn(),
  }));
}

const HEALTHY = {
  status: 'UP', components: { db: 'UP', diskSpace: 'UP' }, uptimeSeconds: 3725,
  checkedAt: new Date().toISOString(),
};

describe('SystemHealth', () => {
  beforeEach(() => {
    vi.mocked(adminSystemApi.health).mockReset();
    vi.mocked(adminSystemApi.recentImports).mockReset();
  });

  it('shows an access-denied message when the account lacks SYSTEM_SETTINGS', () => {
    mockAuth([]);
    vi.mocked(adminSystemApi.health).mockResolvedValue(HEALTHY);
    vi.mocked(adminSystemApi.recentImports).mockResolvedValue([]);

    renderPage();

    expect(screen.getByText("You don't have access to this section")).toBeInTheDocument();
  });

  it('renders the overall status and per-component breakdown', async () => {
    mockAuth(['SYSTEM_SETTINGS']);
    vi.mocked(adminSystemApi.health).mockResolvedValue(HEALTHY);
    vi.mocked(adminSystemApi.recentImports).mockResolvedValue([]);

    renderPage();

    await waitFor(() => expect(screen.getByText('Overall status: UP')).toBeInTheDocument());
    expect(screen.getByText('db')).toBeInTheDocument();
    expect(screen.getByText('diskSpace')).toBeInTheDocument();
  });

  it('shows the empty message when there are no recorded imports', async () => {
    mockAuth(['SYSTEM_SETTINGS']);
    vi.mocked(adminSystemApi.health).mockResolvedValue(HEALTHY);
    vi.mocked(adminSystemApi.recentImports).mockResolvedValue([]);

    renderPage();

    await waitFor(() => expect(screen.getByText('No statement imports recorded yet.')).toBeInTheDocument());
  });

  it('shows a real recent import with the honest hadSkippedRows signal, not a fabricated status', async () => {
    mockAuth(['SYSTEM_SETTINGS']);
    vi.mocked(adminSystemApi.health).mockResolvedValue(HEALTHY);
    vi.mocked(adminSystemApi.recentImports).mockResolvedValue([
      {
        id: 'imp-1', userId: 'user-1', userEmail: 'owner@example.com', fileName: 'messy.csv',
        transactionsImported: 12, transactionsSkipped: 3, hadSkippedRows: true,
        importedAt: new Date().toISOString(),
      },
      {
        id: 'imp-2', userId: 'user-2', userEmail: 'clean@example.com', fileName: 'clean.csv',
        transactionsImported: 8, transactionsSkipped: 0, hadSkippedRows: false,
        importedAt: new Date().toISOString(),
      },
    ]);

    renderPage();

    await waitFor(() => expect(screen.getByText('messy.csv')).toBeInTheDocument());
    expect(screen.getByText(/owner@example.com/)).toBeInTheDocument();
    expect(screen.getByText(/3 skipped/)).toBeInTheDocument();

    expect(screen.getByText('clean.csv')).toBeInTheDocument();
    expect(screen.getByText(/clean@example.com/)).toBeInTheDocument();
    // The clean row's detail line should NOT mention skipped rows at all.
    const cleanRow = screen.getByText('clean.csv').closest('div')!.parentElement!;
    expect(cleanRow.textContent).not.toContain('skipped');
  });
});
