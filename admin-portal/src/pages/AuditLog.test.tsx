import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import AuditLog from './AuditLog';
import { useAdminAuth } from '../context/AdminAuthContext';
import { adminAuditApi } from '../api/endpoints';

vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
vi.mock('../api/endpoints', () => ({
  adminAuditApi: { forUser: vi.fn(), global: vi.fn() },
}));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <AuditLog />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

// AdminLayout always renders Sidebar, which reads `permissions` off this same hook, so every
// mock here must supply it (see MerchantIntelligence.test.tsx's bug-fix comment for why).
function mockAuth(permissions: string[]) {
  vi.mocked(useAdminAuth).mockReturnValue({
    hasPermission: (p: string) => permissions.includes(p),
    permissions,
    fullName: 'Support Admin',
    logout: vi.fn(),
  } as ReturnType<typeof useAdminAuth>);
}

function pagedResponse(content: unknown[], overrides: Partial<Record<string, unknown>> = {}) {
  return {
    content, page: 0, size: 25, totalElements: content.length, totalPages: 1,
    ...overrides,
  };
}

const NO_FILTERS = { q: undefined, dateFrom: undefined, dateTo: undefined, sortDir: 'desc' };

beforeEach(() => {
  localStorage.clear();
});

describe('AuditLog', () => {
  beforeEach(() => {
    vi.mocked(adminAuditApi.global).mockReset();
  });

  it('shows an access-denied message when the account lacks AUDIT_VIEW', () => {
    mockAuth([]);
    vi.mocked(adminAuditApi.global).mockResolvedValue(pagedResponse([]));

    renderPage();

    expect(screen.getByText("You don't have access to this section")).toBeInTheDocument();
  });

  it('shows the empty message when there is no activity yet', async () => {
    mockAuth(['AUDIT_VIEW']);
    vi.mocked(adminAuditApi.global).mockResolvedValue(pagedResponse([]));

    renderPage();

    await waitFor(() => expect(screen.getByText('No activity recorded yet.')).toBeInTheDocument());
  });

  it('groups entries under a "Today" day header and shows action + entity detail', async () => {
    mockAuth(['AUDIT_VIEW']);
    const now = new Date().toISOString();
    vi.mocked(adminAuditApi.global).mockResolvedValue(pagedResponse([
      {
        id: 'log-1', userId: 'user-abcdef12-0000-0000-0000-000000000000', action: 'USER_LOGIN',
        entityType: 'User', entityId: null, metadata: null, requestId: null, createdAt: now,
      },
      {
        id: 'log-2', userId: 'user-abcdef12-0000-0000-0000-000000000000', action: 'MERCHANT_UPDATED',
        entityType: 'Merchant', entityId: 'merchant-12345678', metadata: null, requestId: null, createdAt: now,
      },
    ]));

    renderPage();

    await waitFor(() => expect(screen.getByText('Today')).toBeInTheDocument());
    expect(screen.getByText('USER_LOGIN')).toBeInTheDocument();
    expect(screen.getByText('MERCHANT_UPDATED')).toBeInTheDocument();
    expect(screen.getByText(/Merchant #merchant/)).toBeInTheDocument();
  });

  it('paginates via the next/previous controls', async () => {
    mockAuth(['AUDIT_VIEW']);
    vi.mocked(adminAuditApi.global).mockImplementation((page: number) =>
      Promise.resolve(pagedResponse(
        [{
          id: `log-page-${page}`, userId: 'user-abcdef12-0000-0000-0000-000000000000',
          action: 'ACCOUNT_CREATED', entityType: 'Account', entityId: null, metadata: null,
          requestId: null, createdAt: new Date().toISOString(),
        }],
        { page, totalElements: 50, totalPages: 2 },
      ))
    );
    const user = userEvent.setup();

    renderPage();

    await waitFor(() => expect(screen.getByText('Page 1 of 2')).toBeInTheDocument());

    await user.click(screen.getByText('Page 1 of 2').parentElement!.querySelector('button:last-of-type')!);

    await waitFor(() => expect(adminAuditApi.global).toHaveBeenCalledWith(1, 25, NO_FILTERS));
  });

  it('applies a search filter via the FilterBar', async () => {
    mockAuth(['AUDIT_VIEW']);
    vi.mocked(adminAuditApi.global).mockResolvedValue(pagedResponse([]));
    const user = userEvent.setup();

    renderPage();
    await waitFor(() => expect(adminAuditApi.global).toHaveBeenCalledWith(0, 25, NO_FILTERS));

    await user.type(screen.getByPlaceholderText('Search action or entity type…'), 'MERCHANT_UPDATED');
    await user.click(screen.getByRole('button', { name: 'Search' }));

    await waitFor(() => expect(adminAuditApi.global).toHaveBeenCalledWith(
      0, 25, { q: 'MERCHANT_UPDATED', dateFrom: undefined, dateTo: undefined, sortDir: 'desc' }
    ));
  });

  it('switches sort direction immediately via the sort select', async () => {
    mockAuth(['AUDIT_VIEW']);
    vi.mocked(adminAuditApi.global).mockResolvedValue(pagedResponse([]));
    const user = userEvent.setup();

    renderPage();
    await waitFor(() => expect(adminAuditApi.global).toHaveBeenCalledWith(0, 25, NO_FILTERS));

    await user.selectOptions(screen.getByDisplayValue('Newest first'), 'asc');

    await waitFor(() => expect(adminAuditApi.global).toHaveBeenCalledWith(
      0, 25, { q: undefined, dateFrom: undefined, dateTo: undefined, sortDir: 'asc' }
    ));
  });

  it('saves the current filters as a named view, then applies it back after clearing the search', async () => {
    mockAuth(['AUDIT_VIEW']);
    vi.mocked(adminAuditApi.global).mockResolvedValue(pagedResponse([]));
    const user = userEvent.setup();

    renderPage();
    await waitFor(() => expect(adminAuditApi.global).toHaveBeenCalledWith(0, 25, NO_FILTERS));

    const searchInput = screen.getByPlaceholderText('Search action or entity type…');
    await user.type(searchInput, 'BANK_UPDATED');
    await user.click(screen.getByRole('button', { name: /Views/ }));
    await user.click(screen.getByText('+ Save current filters'));
    await user.type(screen.getByPlaceholderText('View name'), 'Bank changes');
    await user.click(screen.getByRole('button', { name: 'Save' }));

    // Clear the search box so applying the saved view is the only thing that could restore it.
    await user.clear(searchInput);
    await user.click(screen.getByText('Bank changes'));

    await waitFor(() => expect(adminAuditApi.global).toHaveBeenCalledWith(
      0, 25, { q: 'BANK_UPDATED', dateFrom: undefined, dateTo: undefined, sortDir: 'desc' }
    ));
    expect(searchInput).toHaveValue('BANK_UPDATED');

    // And it really did persist to localStorage under this page's own key (useSavedViews.test.ts
    // covers the hook's persistence mechanics in isolation; this just proves AuditLog wired the
    // right storage key through, not a made-up one).
    const stored = JSON.parse(localStorage.getItem('finora-admin-views-audit-log')!);
    expect(stored).toEqual([{ name: 'Bank changes', values: { q: 'BANK_UPDATED', dateFrom: '', dateTo: '', sortDir: 'desc' } }]);
  });
});
