import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import SupportTickets from './SupportTickets';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminSupportTicketApi } from '../api/endpoints';
import type { SupportTicketRow } from '../types';

vi.mock('../context/ThemeContext', () => ({
  useTheme: () => ({ theme: 'system', resolvedTheme: 'light', setTheme: vi.fn() }),
}));
vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
vi.mock('../api/endpoints', () => ({
  adminSupportTicketApi: { list: vi.fn() },
}));

const row: SupportTicketRow = {
  id: '11111111-1111-1111-1111-111111111111',
  ticketNumber: 'SUP-000042',
  userId: '22222222-2222-2222-2222-222222222222',
  category: 'STATEMENT_IMPORT',
  subject: 'Import stuck at 60%',
  status: 'OPEN',
  claimedByAdminId: null,
  createdAt: '2026-09-01T08:00:00Z',
  updatedAt: '2026-09-01T08:00:00Z',
};

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <SupportTickets />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

function mockAuth(permissions: string[]) {
  vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({
    hasPermission: (p: string) => permissions.includes(p),
    permissions,
  }));
}

describe('SupportTickets', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(adminSupportTicketApi.list).mockResolvedValue({
      content: [row], page: 0, size: 25, totalElements: 1, totalPages: 1,
    });
  });

  it('is gated on SUPPORT_MANAGE, not on any permission an admin happens to hold', () => {
    mockAuth(['TRUST_REVIEW_MANAGE']);
    renderPage();

    expect(screen.getByText(/don't have access to this section/i)).toBeInTheDocument();
    expect(adminSupportTicketApi.list).not.toHaveBeenCalled();
  });

  it('lists a ticket with its number, subject, category, status and claimed-by column', async () => {
    mockAuth(['SUPPORT_MANAGE']);
    renderPage();

    expect(await screen.findByText('SUP-000042')).toBeInTheDocument();
    // Scoped to the table, not the page as a whole: "Statement import" also appears as an
    // <option> inside the category filter dropdown, and getByText fails on more than one match.
    const table = within(screen.getByRole('table'));
    expect(table.getByText('Import stuck at 60%')).toBeInTheDocument();
    expect(table.getByText('Statement import')).toBeInTheDocument();
    expect(table.getByText('OPEN')).toBeInTheDocument();
    expect(table.getByText('—')).toBeInTheDocument(); // unclaimed
  });

  it('links the ticket number into the detail route', async () => {
    mockAuth(['SUPPORT_MANAGE']);
    renderPage();

    const link = await screen.findByRole('link', { name: 'SUP-000042' });
    expect(link).toHaveAttribute('href', '/support-tickets/11111111-1111-1111-1111-111111111111');
  });

  it('refetches with the status filter applied', async () => {
    mockAuth(['SUPPORT_MANAGE']);
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('SUP-000042');

    await user.selectOptions(screen.getByLabelText(/filter by status/i), 'RESOLVED');

    expect(adminSupportTicketApi.list).toHaveBeenLastCalledWith(
      expect.objectContaining({ status: 'RESOLVED', page: 0 })
    );
  });
});
