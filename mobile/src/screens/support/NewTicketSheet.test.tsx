import { act, fireEvent, render, screen } from '@testing-library/react-native';
import { NewTicketSheet } from './NewTicketSheet';
import { supportApi, type SupportTicketDetail } from '../../api/endpoints';
import { AttachmentTooLargeError, pickTicketAttachment } from '../../lib/ticketAttachment';

jest.mock('../../api/endpoints', () => ({
  supportApi: { create: jest.fn() },
}));
jest.mock('../../lib/ticketAttachment', () => {
  class AttachmentTooLargeError extends Error {}
  return { AttachmentTooLargeError, pickTicketAttachment: jest.fn() };
});

const api = supportApi as jest.Mocked<typeof supportApi>;
const picker = pickTicketAttachment as jest.MockedFunction<typeof pickTicketAttachment>;

const onClose = jest.fn();
const onCreated = jest.fn();

function renderSheet() {
  return render(<NewTicketSheet onClose={onClose} onCreated={onCreated} />);
}

async function settle() {
  await act(async () => {});
}

function ticket(overrides: Partial<SupportTicketDetail> = {}): SupportTicketDetail {
  return {
    id: 'ticket-1', ticketNumber: 'SUP-000001', userId: 'user-1', category: 'STATEMENT_IMPORT',
    subject: 'Import stuck', status: 'OPEN', claimedByAdminId: null,
    createdAt: '2026-09-04T10:00:00Z', updatedAt: '2026-09-04T10:00:00Z',
    description: 'Progress bar froze at 60%.', source: 'MOBILE_ANDROID', appVersion: '1.0.0',
    resolvedAt: null, closedAt: null, attachments: [],
    ...overrides,
  };
}

describe('NewTicketSheet', () => {
  beforeEach(() => {
    onClose.mockReset();
    onCreated.mockReset();
    api.create.mockReset();
    picker.mockReset();
  });

  it('keeps Submit disabled until both subject and description are filled', () => {
    renderSheet();

    expect(screen.getByRole('button', { name: /submit ticket/i })).toBeDisabled();

    fireEvent.changeText(screen.getByLabelText('Subject'), 'Import stuck');
    expect(screen.getByRole('button', { name: /submit ticket/i })).toBeDisabled();

    fireEvent.changeText(screen.getByLabelText('Description'), 'Progress bar froze at 60%.');
    expect(screen.getByRole('button', { name: /submit ticket/i })).not.toBeDisabled();
  });

  it('submits the trimmed category/subject/description and hands the created ticket back', async () => {
    const created = ticket();
    api.create.mockResolvedValue(created);
    renderSheet();

    fireEvent.changeText(screen.getByLabelText('Subject'), '  Import stuck  ');
    fireEvent.changeText(screen.getByLabelText('Description'), '  Progress bar froze at 60%.  ');
    fireEvent.press(screen.getByRole('button', { name: /submit ticket/i }));
    await settle();

    expect(api.create).toHaveBeenCalledWith({
      category: 'STATEMENT_IMPORT', subject: 'Import stuck', description: 'Progress bar froze at 60%.', file: null,
    });
    expect(onCreated).toHaveBeenCalledWith(created);
  });

  it('lets the user change the category via the picker', async () => {
    api.create.mockResolvedValue(ticket());
    renderSheet();

    fireEvent.press(screen.getByLabelText(/^Category:/));
    fireEvent.press(screen.getByText('Technical issue'));

    expect(screen.getByLabelText(/^Category: Technical issue/)).toBeTruthy();
  });

  it('attaches the picked file to the create call', async () => {
    picker.mockResolvedValue({ uri: 'file:///cache/screenshot.png', name: 'screenshot.png', type: 'image/png' });
    api.create.mockResolvedValue(ticket());
    renderSheet();

    fireEvent.changeText(screen.getByLabelText('Subject'), 'Import stuck');
    fireEvent.changeText(screen.getByLabelText('Description'), 'Details.');
    fireEvent.press(screen.getByLabelText('Choose an attachment'));
    await settle();

    expect(screen.getByText('screenshot.png')).toBeTruthy();

    fireEvent.press(screen.getByRole('button', { name: /submit ticket/i }));
    await settle();

    expect(api.create).toHaveBeenCalledWith(expect.objectContaining({
      file: { uri: 'file:///cache/screenshot.png', name: 'screenshot.png', type: 'image/png' },
    }));
  });

  it('shows a size error from the picker and does not attach a file', async () => {
    picker.mockRejectedValue(new AttachmentTooLargeError('Attachments are limited to 5 MB.'));
    renderSheet();

    fireEvent.press(screen.getByLabelText('Choose an attachment'));
    await settle();

    expect(screen.getByText('Attachments are limited to 5 MB.')).toBeTruthy();
    expect(screen.getByText(/choose a file/i)).toBeTruthy();
  });

  it('shows the server error message and does not call onCreated on failure', async () => {
    api.create.mockRejectedValue({
      isAxiosError: true,
      response: { status: 400, data: { message: 'Something is broken' } },
    });
    renderSheet();

    fireEvent.changeText(screen.getByLabelText('Subject'), 'Import stuck');
    fireEvent.changeText(screen.getByLabelText('Description'), 'Details.');
    fireEvent.press(screen.getByRole('button', { name: /submit ticket/i }));
    await settle();

    expect(screen.getByText('Something is broken')).toBeTruthy();
    expect(onCreated).not.toHaveBeenCalled();
  });

  it('calls onClose when Cancel is pressed', () => {
    renderSheet();

    fireEvent.press(screen.getByRole('button', { name: /cancel/i }));
    expect(onClose).toHaveBeenCalled();
  });
});
