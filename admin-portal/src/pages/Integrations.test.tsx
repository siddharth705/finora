import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Integrations from './Integrations';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminIntegrationsApi } from '../api/endpoints';
import type { IntegrationsOverviewDto } from '../types';

// Same reason as SystemHealth.test.tsx: AdminLayout renders ThemeToggle/Sidebar, which need
// these two contexts mocked or every AdminLayout-wrapped page throws before any assertion runs.
vi.mock('../context/ThemeContext', () => ({
  useTheme: () => ({ theme: 'system', resolvedTheme: 'light', setTheme: vi.fn() }),
}));
vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
vi.mock('../api/endpoints', () => ({
  adminIntegrationsApi: { overview: vi.fn() },
}));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <Integrations />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

function mockAuth(permissions: string[]) {
  vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({
    hasPermission: (p: string) => permissions.includes(p),
    permissions,
    fullName: 'Support Admin',
    logout: vi.fn(),
  }));
}

const OVERVIEW: IntegrationsOverviewDto = {
  integrations: [
    { name: 'Gmail Sync', category: 'Integrations', description: 'Reads bank alert emails via Gmail API', status: 'DEGRADED', detail: 'OAuth client not configured' },
    { name: 'Statement Storage', category: 'Platform', description: 'Stores uploaded bank statement files', status: 'UP', detail: 'Using r2' },
  ],
  upcoming: [
    { name: 'Payment Provider (Stripe/Razorpay)', description: 'Subscription billing -- schema exists, no live provider wired yet' },
  ],
};

describe('Integrations', () => {
  beforeEach(() => {
    vi.mocked(adminIntegrationsApi.overview).mockReset();
  });

  it('shows an access-denied message when the account lacks PLATFORM_DIAGNOSTICS_VIEW', () => {
    mockAuth([]);
    vi.mocked(adminIntegrationsApi.overview).mockResolvedValue(OVERVIEW);

    renderPage();

    expect(screen.getByText("You don't have access to this section")).toBeInTheDocument();
  });

  it('renders live integrations with their status and the upcoming section', async () => {
    mockAuth(['PLATFORM_DIAGNOSTICS_VIEW']);
    vi.mocked(adminIntegrationsApi.overview).mockResolvedValue(OVERVIEW);

    renderPage();

    await waitFor(() => expect(screen.getByText('Gmail Sync')).toBeInTheDocument());
    expect(screen.getByText('DEGRADED')).toBeInTheDocument();
    expect(screen.getByText('Statement Storage')).toBeInTheDocument();
    expect(screen.getByText('UP')).toBeInTheDocument();
    expect(screen.getByText('Payment Provider (Stripe/Razorpay)')).toBeInTheDocument();
  });

  it('shows the empty-state message when there are no live integrations reporting', async () => {
    mockAuth(['PLATFORM_DIAGNOSTICS_VIEW']);
    vi.mocked(adminIntegrationsApi.overview).mockResolvedValue({ integrations: [], upcoming: [] });

    renderPage();

    await waitFor(() => expect(screen.getByText('No integrations reporting health right now.')).toBeInTheDocument());
    expect(screen.getByText('Nothing planned right now.')).toBeInTheDocument();
  });

  /**
   * Same bug class as SystemHealth.test.tsx: a failed request must render a visible error, not
   * a blank page, on a page whose entire purpose is telling an admin what's connected.
   */
  it('shows an error message instead of a blank page when the overview request fails', async () => {
    mockAuth(['PLATFORM_DIAGNOSTICS_VIEW']);
    vi.mocked(adminIntegrationsApi.overview).mockRejectedValue(new Error('network error'));

    renderPage();

    await waitFor(() => expect(screen.getByText(/Couldn't load integrations/)).toBeInTheDocument());
  });
});
