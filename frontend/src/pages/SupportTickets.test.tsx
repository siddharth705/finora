import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import SupportTickets from './SupportTickets';
import { supportApi } from '../api/endpoints';
import type { SupportTicketSummary } from '../api/endpoints';

const navigateSpy = vi.fn();
vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>();
  return { ...actual, useNavigate: () => navigateSpy };
});

vi.mock('../api/endpoints', () => ({
  supportApi: { list: vi.fn(), create: vi.fn(), detail: vi.fn(), downloadAttachment: vi.fn() },
}));

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

function summary(overrides: Partial<SupportTicketSummary> = {}): SupportTicketSummary {
  return {
    id: 'ticket-1',
    ticketNumber: 'SUP-000001',
    userId: 'user-1',
    category: 'STATEMENT_IMPORT',
    subject: 'Import stuck',
    status: 'OPEN',
    claimedByAdminId: null,
    createdAt: '2026-09-04T10:00:00Z',
    updatedAt: '2026-09-04T10:00:00Z',
    ...overrides,
  };
}

describe('SupportTickets', () => {
  beforeEach(() => {
    navigateSpy.mockReset();
    vi.mocked(supportApi.list).mockReset();
  });

  it('shows the empty state when the user has filed no tickets', async () => {
    vi.mocked(supportApi.list).mockResolvedValue({ content: [], page: 0, size: 25, totalElements: 0, totalPages: 0 });
    renderPage();

    expect(await screen.findByText(/no support tickets yet/i)).toBeInTheDocument();
  });

  it('lists a ticket with its number, subject and status', async () => {
    vi.mocked(supportApi.list).mockResolvedValue({
      content: [summary({ status: 'IN_PROGRESS' })],
      page: 0, size: 25, totalElements: 1, totalPages: 1,
    });
    renderPage();

    expect(await screen.findByText('Import stuck')).toBeInTheDocument();
    expect(screen.getByText('SUP-000001')).toBeInTheDocument();
    expect(screen.getByText('In Progress')).toBeInTheDocument();
  });

  it('navigates to the ticket detail route when a row is clicked', async () => {
    const user = userEvent.setup();
    vi.mocked(supportApi.list).mockResolvedValue({
      content: [summary()], page: 0, size: 25, totalElements: 1, totalPages: 1,
    });
    renderPage();

    await user.click(await screen.findByText('Import stuck'));
    expect(navigateSpy).toHaveBeenCalledWith('/app/support/ticket-1');
  });

  it('opens the New Ticket modal from the button', async () => {
    const user = userEvent.setup();
    vi.mocked(supportApi.list).mockResolvedValue({ content: [], page: 0, size: 25, totalElements: 0, totalPages: 0 });
    renderPage();
    await screen.findByText(/no support tickets yet/i);

    await user.click(screen.getByRole('button', { name: /new ticket/i }));
    expect(screen.getByRole('heading', { name: /new support ticket/i })).toBeInTheDocument();
  });
});
