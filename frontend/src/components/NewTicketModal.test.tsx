import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { NewTicketModal } from './NewTicketModal';
import { supportApi } from '../api/endpoints';
import type { SupportTicketDetail } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  supportApi: { create: vi.fn() },
}));

function detail(overrides: Partial<SupportTicketDetail> = {}): SupportTicketDetail {
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
    description: 'Progress bar froze at 60%.',
    source: 'WEB',
    appVersion: null,
    resolvedAt: null,
    closedAt: null,
    attachments: [],
    ...overrides,
  };
}

describe('NewTicketModal', () => {
  beforeEach(() => {
    vi.mocked(supportApi.create).mockReset();
  });

  it('keeps Submit disabled until both subject and description are filled', async () => {
    const user = userEvent.setup();
    render(<NewTicketModal onClose={vi.fn()} onCreated={vi.fn()} />);

    const submit = screen.getByRole('button', { name: /submit ticket/i });
    expect(submit).toBeDisabled();

    await user.type(screen.getByLabelText(/subject/i), 'Import stuck');
    expect(submit).toBeDisabled();

    await user.type(screen.getByLabelText(/description/i), 'Progress bar froze at 60%.');
    expect(submit).toBeEnabled();
  });

  it('submits the trimmed category/subject/description and hands the created ticket back', async () => {
    const user = userEvent.setup();
    const created = detail();
    vi.mocked(supportApi.create).mockResolvedValue(created);
    const onCreated = vi.fn();
    render(<NewTicketModal onClose={vi.fn()} onCreated={onCreated} />);

    await user.selectOptions(screen.getByLabelText(/category/i), 'TECHNICAL_ISSUE');
    await user.type(screen.getByLabelText(/subject/i), '  Import stuck  ');
    await user.type(screen.getByLabelText(/description/i), '  Progress bar froze at 60%.  ');
    await user.click(screen.getByRole('button', { name: /submit ticket/i }));

    await waitFor(() => expect(onCreated).toHaveBeenCalled());
    expect(supportApi.create).toHaveBeenCalledWith({
      category: 'TECHNICAL_ISSUE',
      subject: 'Import stuck',
      description: 'Progress bar froze at 60%.',
      file: null,
    });
    expect(onCreated).toHaveBeenCalledWith(created);
  });

  it('rejects a file over 5 MB client-side without ever calling the API', async () => {
    const user = userEvent.setup();
    render(<NewTicketModal onClose={vi.fn()} onCreated={vi.fn()} />);

    const tooBig = new File([new Uint8Array(1)], 'screenshot.png', { type: 'image/png' });
    Object.defineProperty(tooBig, 'size', { value: 6 * 1024 * 1024 });

    await user.upload(screen.getByLabelText(/attachment/i), tooBig);

    expect(await screen.findByText(/limited to 5 mb/i)).toBeInTheDocument();
    await user.type(screen.getByLabelText(/subject/i), 'Import stuck');
    await user.type(screen.getByLabelText(/description/i), 'Details here.');
    expect(screen.getByRole('button', { name: /submit ticket/i })).toBeDisabled();
  });

  it('shows the server error message and leaves the modal open on failure', async () => {
    const user = userEvent.setup();
    vi.mocked(supportApi.create).mockRejectedValue({ response: { data: { message: 'Something is broken' } } });
    const onCreated = vi.fn();
    render(<NewTicketModal onClose={vi.fn()} onCreated={onCreated} />);

    await user.type(screen.getByLabelText(/subject/i), 'Import stuck');
    await user.type(screen.getByLabelText(/description/i), 'Details here.');
    await user.click(screen.getByRole('button', { name: /submit ticket/i }));

    expect(await screen.findByText('Something is broken')).toBeInTheDocument();
    expect(onCreated).not.toHaveBeenCalled();
  });

  it('calls onClose when Cancel is clicked', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    render(<NewTicketModal onClose={onClose} onCreated={vi.fn()} />);

    await user.click(screen.getByRole('button', { name: /cancel/i }));
    expect(onClose).toHaveBeenCalled();
  });
});
