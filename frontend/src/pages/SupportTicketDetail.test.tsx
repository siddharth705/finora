import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import SupportTicketDetail from './SupportTicketDetail';
import { supportApi } from '../api/endpoints';
import type { SupportTicketDetail as SupportTicketDetailDto } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  supportApi: { detail: vi.fn(), downloadAttachment: vi.fn() },
}));

function renderPage(ticketId = 'ticket-1') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/app/support/${ticketId}`]}>
        <Routes>
          <Route path="/app/support/:ticketId" element={<SupportTicketDetail />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

function detail(overrides: Partial<SupportTicketDetailDto> = {}): SupportTicketDetailDto {
  return {
    id: 'ticket-1',
    ticketNumber: 'SUP-000001',
    userId: 'user-1',
    category: 'TECHNICAL_ISSUE',
    subject: 'Import stuck',
    status: 'OPEN',
    claimedByAdminId: null,
    createdAt: '2026-09-04T10:00:00Z',
    updatedAt: '2026-09-04T10:00:00Z',
    description: 'Progress bar froze at 60%.',
    source: 'WEB',
    appVersion: null,
    resolvedAt: null,
    closedAt: null,
    attachments: [],
    ...overrides,
  };
}

describe('SupportTicketDetail', () => {
  beforeEach(() => {
    vi.mocked(supportApi.detail).mockReset();
    vi.mocked(supportApi.downloadAttachment).mockReset();
  });

  it('renders the ticket subject, description and status', async () => {
    vi.mocked(supportApi.detail).mockResolvedValue(detail());
    renderPage();

    expect(await screen.findByText('Import stuck')).toBeInTheDocument();
    expect(screen.getByText('Progress bar froze at 60%.')).toBeInTheDocument();
    expect(screen.getByText('Open')).toBeInTheDocument();
    expect(screen.getByText('SUP-000001 · Technical issue')).toBeInTheDocument();
  });

  it('shows a not-found message when the ticket 404s (not the caller\'s, or does not exist)', async () => {
    vi.mocked(supportApi.detail).mockRejectedValue({ response: { status: 404 } });
    renderPage();

    expect(await screen.findByText(/ticket not found/i)).toBeInTheDocument();
  });

  it('lists an attachment and downloads it when clicked', async () => {
    const user = userEvent.setup();
    vi.mocked(supportApi.detail).mockResolvedValue(detail({
      attachments: [{ id: 'att-1', filename: 'screenshot.png', contentType: 'image/png', sizeBytes: 2048 }],
    }));
    vi.mocked(supportApi.downloadAttachment).mockResolvedValue(undefined);
    renderPage();

    const link = await screen.findByRole('button', { name: /screenshot\.png/i });
    await user.click(link);

    expect(supportApi.downloadAttachment).toHaveBeenCalledWith('ticket-1', 'att-1', 'screenshot.png');
  });

  it('tells the user a resolved ticket cannot be reopened', async () => {
    vi.mocked(supportApi.detail).mockResolvedValue(detail({ status: 'RESOLVED' }));
    renderPage();

    expect(await screen.findByText(/can't be reopened/i)).toBeInTheDocument();
  });
});
