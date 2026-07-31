import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import MerchantIntelligence from './MerchantIntelligence';
import { useAdminAuth } from '../context/AdminAuthContext';
import { adminMerchantsApi } from '../api/endpoints';

vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
vi.mock('../api/endpoints', () => ({
  adminMerchantsApi: { platformStats: vi.fn() },
}));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <MerchantIntelligence />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('MerchantIntelligence', () => {
  beforeEach(() => {
    vi.mocked(useAdminAuth).mockReset();
    vi.mocked(adminMerchantsApi.platformStats).mockReset();
  });

  it('shows an access-denied message when the account lacks MERCHANT_MANAGE', () => {
    vi.mocked(useAdminAuth).mockReturnValue({
      hasPermission: () => false,
      // Bug fix: AdminLayout always renders Sidebar regardless of this page's own permission
      // gate, and Sidebar destructures `permissions` off this same hook and calls
      // permissions.includes(...) while building its nav -- a mock that only stubs
      // hasPermission (as this one used to) leaves `permissions` undefined and throws
      // "Cannot read properties of undefined (reading 'includes')" before any assertion runs.
      permissions: [],
      fullName: 'Support Admin',
      logout: vi.fn(),
    } as ReturnType<typeof useAdminAuth>);
    vi.mocked(adminMerchantsApi.platformStats).mockResolvedValue([]);

    renderPage();

    expect(screen.getByText("You don't have access to this section")).toBeInTheDocument();
  });

  it('renders the platform catalog for an account with MERCHANT_MANAGE', async () => {
    vi.mocked(useAdminAuth).mockReturnValue({
      hasPermission: (p: string) => p === 'MERCHANT_MANAGE',
      permissions: ['MERCHANT_MANAGE'],
      fullName: 'Support Admin',
      logout: vi.fn(),
    } as ReturnType<typeof useAdminAuth>);
    vi.mocked(adminMerchantsApi.platformStats).mockResolvedValue([
      { canonicalName: 'Swiggy', userCount: 42, rowCount: 45 },
    ]);

    renderPage();

    await waitFor(() => expect(screen.getByText('Swiggy')).toBeInTheDocument());
    expect(screen.getByText('42')).toBeInTheDocument();
    expect(screen.getByText('45')).toBeInTheDocument();
  });

  it('shows the empty message when the platform has no merchants yet', async () => {
    vi.mocked(useAdminAuth).mockReturnValue({
      hasPermission: (p: string) => p === 'MERCHANT_MANAGE',
      permissions: ['MERCHANT_MANAGE'],
      fullName: 'Support Admin',
      logout: vi.fn(),
    } as ReturnType<typeof useAdminAuth>);
    vi.mocked(adminMerchantsApi.platformStats).mockResolvedValue([]);

    renderPage();

    await waitFor(() => expect(screen.getByText('No merchants recorded on the platform yet.')).toBeInTheDocument());
  });
});
