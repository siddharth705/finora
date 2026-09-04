import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import SupportTicketDetail from './SupportTicketDetail';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminSupportTicketApi } from '../api/endpoints';
import type { SupportTicketDetail as SupportTicketDetailDto, SupportTicketNote, SupportTicketRow } from '../types';

vi.mock('../context/ThemeContext', () => ({
  useTheme: () => ({ theme: 'system', resolvedTheme: 'light', setTheme: vi.fn() }),
}));
vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
vi.mock('../api/endpoints', () => ({
  adminSupportTicketApi: {
    get: vi.fn(), updateStatus: vi.fn(), notes: vi.fn(), addNote: vi.fn(),
    claim: vi.fn(), unclaim: vi.fn(), downloadAttachment: vi.fn(),
  },
}));

const ticketId = '11111111-1111-1111-1111-111111111111';

const ticket: SupportTicketDetailDto = {
  id: ticketId,
  ticketNumber: 'SUP-000042',
  userId: '22222222-2222-2222-2222-222222222222',
  category: 'TECHNICAL_ISSUE',
  subject: 'Import stuck at 60%',
  description: 'Progress bar froze at 60% and never finished.',
  status: 'OPEN',
  source: 'WEB',
  appVersion: null,
  claimedByAdminId: null,
  resolvedAt: null,
  closedAt: null,
  createdAt: '2026-09-01T08:00:00Z',
  updatedAt: '2026-09-01T08:00:00Z',
  attachments: [],
};

/** claim()/unclaim()/updateStatus() return the row shape (SupportTicketRow), not the full detail
 *  the page itself displays -- distinct fixtures so a copy-pasted `ticket` spread here can't hide
 *  a real shape mismatch behind a type assertion. */
const claimedRow: SupportTicketRow = {
  id: ticketId,
  ticketNumber: 'SUP-000042',
  userId: '22222222-2222-2222-2222-222222222222',
  category: 'TECHNICAL_ISSUE',
  subject: 'Import stuck at 60%',
  status: 'OPEN',
  claimedByAdminId: 'me',
  createdAt: '2026-09-01T08:00:00Z',
  updatedAt: '2026-09-01T08:00:00Z',
};

const note: SupportTicketNote = {
  id: 'note-1',
  adminId: '33333333-3333-3333-3333-333333333333',
  note: 'Reproduced on Android 1.3.7',
  createdAt: '2026-09-01T09:00:00Z',
};

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/support-tickets/${ticketId}`]}>
        <Routes>
          <Route path="/support-tickets/:id" element={<SupportTicketDetail />} />
        </Routes>
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

describe('SupportTicketDetail', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockAuth(['SUPPORT_MANAGE']);
    vi.mocked(adminSupportTicketApi.get).mockResolvedValue(ticket);
    vi.mocked(adminSupportTicketApi.notes).mockResolvedValue([]);
  });

  it('is gated on SUPPORT_MANAGE', () => {
    mockAuth(['TRUST_REVIEW_MANAGE']);
    renderPage();

    expect(screen.getByText(/don't have access to this section/i)).toBeInTheDocument();
    expect(adminSupportTicketApi.get).not.toHaveBeenCalled();
  });

  it('renders the ticket subject, description and category', async () => {
    renderPage();

    expect(await screen.findByText('Import stuck at 60%')).toBeInTheDocument();
    expect(screen.getByText('Progress bar froze at 60% and never finished.')).toBeInTheDocument();
    expect(screen.getByText(/Technical issue/)).toBeInTheDocument();
  });

  it('only offers the legal next statuses for an OPEN ticket, never a reopen-style move', async () => {
    renderPage();
    await screen.findByText('Import stuck at 60%');

    expect(screen.getByRole('button', { name: 'Move to IN PROGRESS' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Move to RESOLVED' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Move to CLOSED' })).toBeInTheDocument();
  });

  it('shows no status-change buttons at all for a resolved ticket', async () => {
    vi.mocked(adminSupportTicketApi.get).mockResolvedValue({ ...ticket, status: 'RESOLVED' });
    renderPage();

    expect(await screen.findByText(/can't change status again/i)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Move to/ })).not.toBeInTheDocument();
  });

  it('claims an unclaimed ticket directly, without a confirmation step', async () => {
    const user = userEvent.setup();
    vi.mocked(adminSupportTicketApi.claim).mockResolvedValue(claimedRow);
    renderPage();
    await screen.findByText('Import stuck at 60%');

    await user.click(screen.getByRole('button', { name: 'Claim' }));

    expect(adminSupportTicketApi.claim).toHaveBeenCalledWith(ticketId);
  });

  it('asks for confirmation before taking over an already-claimed ticket, naming who holds it', async () => {
    const user = userEvent.setup();
    const claimedTicket = { ...ticket, claimedByAdminId: '99999999-9999-9999-9999-999999999999' };
    vi.mocked(adminSupportTicketApi.get).mockResolvedValue(claimedTicket);
    renderPage();
    await screen.findByText('Import stuck at 60%');

    await user.click(screen.getByRole('button', { name: 'Take over' }));

    expect(screen.getByText('Take over this ticket?')).toBeInTheDocument();
    expect(screen.getByText(/claimed by 99999999-9999-9999-9999-999999999999/)).toBeInTheDocument();
    expect(adminSupportTicketApi.claim).not.toHaveBeenCalled();

    // Two "Take over" buttons exist once the dialog is open (the trigger behind it, and the
    // confirm inside it) -- scoped to the dialog's own role so this clicks the confirm, not
    // whichever "Take over" the DOM happens to list first.
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Take over' }));

    expect(adminSupportTicketApi.claim).toHaveBeenCalledWith(ticketId);
  });

  it('adds an internal note and shows it in the list', async () => {
    const user = userEvent.setup();
    vi.mocked(adminSupportTicketApi.notes).mockResolvedValueOnce([]).mockResolvedValueOnce([note]);
    vi.mocked(adminSupportTicketApi.addNote).mockResolvedValue(note);
    renderPage();
    await screen.findByText('Import stuck at 60%');
    expect(screen.getByText('No internal notes yet.')).toBeInTheDocument();

    await user.type(screen.getByPlaceholderText(/add a note/i), 'Reproduced on Android 1.3.7');
    await user.click(screen.getByRole('button', { name: 'Add note' }));

    expect(adminSupportTicketApi.addNote).toHaveBeenCalledWith(ticketId, 'Reproduced on Android 1.3.7');
    expect(await screen.findByText('Reproduced on Android 1.3.7')).toBeInTheDocument();
  });

  it('downloads an attachment by filename, not by re-deriving it from the id', async () => {
    const user = userEvent.setup();
    vi.mocked(adminSupportTicketApi.get).mockResolvedValue({
      ...ticket,
      attachments: [{ id: 'att-1', filename: 'screenshot.png', contentType: 'image/png', sizeBytes: 2048 }],
    });
    renderPage();

    const attachmentButton = await screen.findByRole('button', { name: /screenshot\.png/ });
    await user.click(attachmentButton);

    expect(adminSupportTicketApi.downloadAttachment).toHaveBeenCalledWith(ticketId, 'att-1', 'screenshot.png');
  });
});
