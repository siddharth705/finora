import { act, fireEvent, render, screen } from '@testing-library/react-native';
import { ChangeEmailSheet } from './ChangeEmailSheet';
import { emailChangeApi } from '../../api/endpoints';

jest.mock('../../api/endpoints', () => ({
  emailChangeApi: { start: jest.fn() },
}));

const api = emailChangeApi as jest.Mocked<typeof emailChangeApi>;

const onClose = jest.fn();

function renderSheet() {
  return render(<ChangeEmailSheet onClose={onClose} />);
}

async function settle() {
  await act(async () => {});
}

function fillForm() {
  fireEvent.changeText(screen.getByLabelText('New email address'), 'new@example.com');
  fireEvent.changeText(screen.getByLabelText('Current password'), 'CurrentPw1!');
}

describe('ChangeEmailSheet', () => {
  beforeEach(() => {
    onClose.mockReset();
    api.start.mockReset().mockResolvedValue({ sessionId: 'sess-1', devVerifyLink: null });
  });

  it('disables submission until both the new email and current password are filled', () => {
    renderSheet();

    expect(screen.getByRole('button', { name: /Send confirmation link/ })).toBeDisabled();

    fillForm();

    expect(screen.getByRole('button', { name: /Send confirmation link/ })).not.toBeDisabled();
  });

  it('calls emailChangeApi.start with the password-only step-up shape and shows the "check your inbox" state', async () => {
    renderSheet();
    fillForm();

    fireEvent.press(screen.getByRole('button', { name: /Send confirmation link/ }));
    await settle();

    expect(api.start).toHaveBeenCalledWith('CurrentPw1!', null, null, 'new@example.com');
    expect(screen.getByText('Check your inbox')).toBeTruthy();
    expect(screen.getByText(/We sent a confirmation link to new@example.com/)).toBeTruthy();
    // The form is gone -- this is a replacement step, not an overlay on top of it.
    expect(screen.queryByLabelText('Current password')).toBeNull();
  });

  it('shows a server error and stays on the form when start() fails', async () => {
    api.start.mockRejectedValue({
      isAxiosError: true,
      response: { status: 400, data: { message: 'Incorrect password.' } },
    });
    renderSheet();
    fillForm();

    fireEvent.press(screen.getByRole('button', { name: /Send confirmation link/ }));
    await settle();

    expect(screen.getByText('Incorrect password.')).toBeTruthy();
    expect(screen.queryByText('Check your inbox')).toBeNull();
  });

  it('shows the dev-only verify link as plain copyable text when the backend returns one', async () => {
    api.start.mockResolvedValue({
      sessionId: 'sess-1',
      devVerifyLink: 'finora://email-change-verify?sessionId=sess-1&token=raw-token',
    });
    renderSheet();
    fillForm();

    fireEvent.press(screen.getByRole('button', { name: /Send confirmation link/ }));
    await settle();

    expect(screen.getByText('finora://email-change-verify?sessionId=sess-1&token=raw-token')).toBeTruthy();
  });

  it('closes via the Cancel link before submitting', () => {
    renderSheet();

    fireEvent.press(screen.getByRole('button', { name: 'Cancel' }));

    expect(onClose).toHaveBeenCalled();
  });

  it('closes via the Done button after a successful start', async () => {
    renderSheet();
    fillForm();
    fireEvent.press(screen.getByRole('button', { name: /Send confirmation link/ }));
    await settle();

    fireEvent.press(screen.getByRole('button', { name: 'Done' }));

    expect(onClose).toHaveBeenCalled();
  });
});
