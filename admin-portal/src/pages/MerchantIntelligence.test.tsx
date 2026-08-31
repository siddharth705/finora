import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import MerchantIntelligence from './MerchantIntelligence';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminMerchantsApi } from '../api/endpoints';

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
  adminMerchantsApi: { platformStats: vi.fn(), gmailParserStats: vi.fn() },
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
    vi.mocked(adminMerchantsApi.platformStats).mockReset().mockResolvedValue([]);
    vi.mocked(adminMerchantsApi.gmailParserStats).mockReset().mockResolvedValue([]);
  });

  function grantMerchantManage() {
    vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({
      hasPermission: (p: string) => p === 'MERCHANT_MANAGE',
      permissions: ['MERCHANT_MANAGE'],
      fullName: 'Support Admin',
      logout: vi.fn(),
    }));
  }

  it('shows an access-denied message when the account lacks MERCHANT_MANAGE', () => {
    vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({
      hasPermission: () => false,
      // Bug fix: AdminLayout always renders Sidebar regardless of this page's own permission
      // gate, and Sidebar destructures `permissions` off this same hook and calls
      // permissions.includes(...) while building its nav -- a mock that only stubs
      // hasPermission (as this one used to) leaves `permissions` undefined and throws
      // "Cannot read properties of undefined (reading 'includes')" before any assertion runs.
      permissions: [],
      fullName: 'Support Admin',
      logout: vi.fn(),
    }));

    renderPage();

    expect(screen.getByText("You don't have access to this section")).toBeInTheDocument();
  });

  it('renders the platform catalog for an account with MERCHANT_MANAGE', async () => {
    grantMerchantManage();
    vi.mocked(adminMerchantsApi.platformStats).mockResolvedValue([
      { canonicalName: 'Swiggy', userCount: 42, rowCount: 45 },
    ]);

    renderPage();

    await waitFor(() => expect(screen.getByText('Swiggy')).toBeInTheDocument());
    expect(screen.getByText('42')).toBeInTheDocument();
    expect(screen.getByText('45')).toBeInTheDocument();
  });

  it('shows the empty message when the platform has no merchants yet', async () => {
    grantMerchantManage();

    renderPage();

    await waitFor(() => expect(screen.getByText('No merchants recorded on the platform yet.')).toBeInTheDocument());
  });

  describe('Gmail parser health', () => {
    it('renders a row with success rate and every outcome count', async () => {
      grantMerchantManage();
      vi.mocked(adminMerchantsApi.gmailParserStats).mockResolvedValue([
        {
          domain: 'amazon.in', merchant: 'Amazon', parsed: 8, parseFailed: 2,
          skippedNotReceipt: 1, noParserYet: 0, successRate: 0.8,
          lastSeen: '2026-08-14T10:00:00Z',
        },
      ]);

      renderPage();

      await waitFor(() => expect(screen.getByText('Amazon')).toBeInTheDocument());
      expect(screen.getByText('amazon.in')).toBeInTheDocument();
      expect(screen.getByText('80%')).toBeInTheDocument();
      expect(screen.getByText('8')).toBeInTheDocument();
      expect(screen.getByText('2')).toBeInTheDocument();
    });

    it('shows "No parser yet" instead of 0% for a domain with only coverage-gap traffic', async () => {
      grantMerchantManage();
      vi.mocked(adminMerchantsApi.gmailParserStats).mockResolvedValue([
        {
          domain: 'newmerchant.example', merchant: 'New Merchant', parsed: 0,
          parseFailed: 0, skippedNotReceipt: 0, noParserYet: 6, successRate: null,
          lastSeen: '2026-08-14T10:00:00Z',
        },
      ]);

      renderPage();

      await waitFor(() => expect(screen.getByText('New Merchant')).toBeInTheDocument());
      expect(screen.getByText('No parser yet')).toBeInTheDocument();
      expect(screen.queryByText('0%')).not.toBeInTheDocument();
    });

    it('shows the empty message when nothing has been processed in the window', async () => {
      grantMerchantManage();

      renderPage();

      await waitFor(() => expect(
        screen.getByText(/No Gmail receipts processed in the last \d+ days\./)
      ).toBeInTheDocument());
    });
  });
});
