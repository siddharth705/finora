import { act, fireEvent, render, screen } from '@testing-library/react-native';
import { FeedbackSheet } from './FeedbackSheet';
import { feedbackApi } from '../../api/endpoints';

jest.mock('../../api/endpoints', () => ({
  feedbackApi: { submit: jest.fn() },
}));

const api = feedbackApi as jest.Mocked<typeof feedbackApi>;
const onClose = jest.fn();

function renderSheet() {
  return render(<FeedbackSheet onClose={onClose} />);
}

async function settle() {
  await act(async () => {});
}

describe('FeedbackSheet', () => {
  beforeEach(() => {
    onClose.mockReset();
    api.submit.mockReset();
  });

  it('keeps Send disabled until a message is entered', () => {
    renderSheet();
    expect(screen.getByRole('button', { name: /send feedback/i })).toBeDisabled();
  });

  it('defaults to GENERAL / OTHER and submits the trimmed message', async () => {
    api.submit.mockResolvedValue({
      id: 'fb-1', userId: 'user-1', type: 'GENERAL', context: 'OTHER', source: 'MOBILE_ANDROID',
      message: 'Great app', createdAt: '2026-09-04T10:00:00Z',
    });
    renderSheet();

    fireEvent.changeText(screen.getByLabelText('Your feedback'), '  Great app  ');
    fireEvent.press(screen.getByRole('button', { name: /send feedback/i }));
    await settle();

    expect(api.submit).toHaveBeenCalledWith({ type: 'GENERAL', context: 'OTHER', message: 'Great app' });
    expect(screen.getByText('Thanks for the feedback')).toBeTruthy();
  });

  it('sends the selected type and context', async () => {
    api.submit.mockResolvedValue({
      id: 'fb-1', userId: 'user-1', type: 'BUG', context: 'IMPORT_FLOW', source: 'MOBILE_ANDROID',
      message: 'Import crashed', createdAt: '2026-09-04T10:00:00Z',
    });
    renderSheet();

    fireEvent.press(screen.getByText('Something’s broken'));
    fireEvent.press(screen.getByLabelText(/^About:/));
    fireEvent.press(screen.getByText('Importing a statement'));
    fireEvent.changeText(screen.getByLabelText('Your feedback'), 'Import crashed');
    fireEvent.press(screen.getByRole('button', { name: /send feedback/i }));
    await settle();

    expect(api.submit).toHaveBeenCalledWith({ type: 'BUG', context: 'IMPORT_FLOW', message: 'Import crashed' });
  });

  it('shows the server error message on failure and does not show the success state', async () => {
    api.submit.mockRejectedValue({ isAxiosError: true, response: { status: 400, data: { message: 'Could not save' } } });
    renderSheet();

    fireEvent.changeText(screen.getByLabelText('Your feedback'), 'Something');
    fireEvent.press(screen.getByRole('button', { name: /send feedback/i }));
    await settle();

    expect(screen.getByText('Could not save')).toBeTruthy();
    expect(screen.queryByText('Thanks for the feedback')).toBeNull();
  });

  it('calls onClose when Cancel is pressed', () => {
    renderSheet();
    fireEvent.press(screen.getByRole('button', { name: /cancel/i }));
    expect(onClose).toHaveBeenCalled();
  });
});
