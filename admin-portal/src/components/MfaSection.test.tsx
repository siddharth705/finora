import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MfaSection } from './MfaSection';
import { adminMfaApi, userApi } from '../api/endpoints';
import { AUTH_MFA_NOT_AVAILABLE, AUTH_MFA_INVALID_CODE } from '../api/errorCodes';

vi.mock('../api/endpoints', () => ({
  adminMfaApi: { status: vi.fn(), enroll: vi.fn(), confirm: vi.fn(), disable: vi.fn() },
  userApi: { get: vi.fn() },
}));

const notifySuccess = vi.fn();
const notifyError = vi.fn();
vi.mock('../context/NotificationContext', () => ({
  useNotify: () => ({ success: notifySuccess, error: notifyError }),
}));

function apiError(errorCode: string, message = 'error') {
  return { response: { data: { errorCode, message } } };
}

function renderSection() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MfaSection />
    </QueryClientProvider>
  );
}

function mockClipboard() {
  Object.defineProperty(navigator, 'clipboard', {
    value: { writeText: vi.fn().mockResolvedValue(undefined) },
    configurable: true,
  });
}

describe('MfaSection', () => {
  beforeEach(() => {
    vi.mocked(adminMfaApi.status).mockReset();
    vi.mocked(adminMfaApi.enroll).mockReset();
    vi.mocked(adminMfaApi.confirm).mockReset();
    vi.mocked(adminMfaApi.disable).mockReset();
    vi.mocked(userApi.get).mockReset().mockResolvedValue({ phoneNumber: '+919876543705', signInMethod: 'PASSWORD' }); // synthetic-ok
    notifySuccess.mockReset();
    notifyError.mockReset();
    mockClipboard();
  });

  it('shows a friendly message, not an error, when the feature is off (AUTH_MFA_NOT_AVAILABLE)', async () => {
    vi.mocked(adminMfaApi.status).mockRejectedValue(apiError(AUTH_MFA_NOT_AVAILABLE));

    renderSection();

    expect(await screen.findByText(/isn't turned on for this installation yet/i)).toBeInTheDocument();
  });

  it('shows a generic error for any other status failure', async () => {
    vi.mocked(adminMfaApi.status).mockRejectedValue(apiError('SOME_OTHER_CODE'));

    renderSection();

    expect(await screen.findByText(/could not load your two-factor authentication status/i)).toBeInTheDocument();
  });

  describe('when not yet enrolled', () => {
    beforeEach(() => {
      vi.mocked(adminMfaApi.status).mockResolvedValue({ enabled: false });
    });

    it('shows the enrollment intro', async () => {
      renderSection();

      expect(await screen.findByRole('button', { name: /set up two-factor authentication/i })).toBeInTheDocument();
    });

    it('walks through enroll -> confirm -> recovery codes -> done', async () => {
      const user = userEvent.setup();
      vi.mocked(adminMfaApi.enroll).mockResolvedValue({
        secret: 'JBSWY3DPEHPK3PXP',
        provisioningUri: 'otpauth://totp/Finora%20Admin:admin@finora.test?secret=JBSWY3DPEHPK3PXP&issuer=Finora%20Admin',
      });
      vi.mocked(adminMfaApi.confirm).mockResolvedValue({ recoveryCodes: ['AAAAA-BBBBB', 'CCCCC-DDDDD'] });
      // Re-queried after enrolling to reflect the now-enabled state.
      vi.mocked(adminMfaApi.status).mockResolvedValueOnce({ enabled: false }).mockResolvedValue({ enabled: true });

      renderSection();
      await user.click(await screen.findByRole('button', { name: /set up two-factor authentication/i }));

      expect(await screen.findByText('JBSWY3DPEHPK3PXP')).toBeInTheDocument();

      await user.type(screen.getByLabelText(/code from your app/i), '123456');
      await user.click(screen.getByRole('button', { name: /confirm and turn on/i }));

      expect(adminMfaApi.confirm).toHaveBeenCalledWith('123456');
      expect(await screen.findByText('AAAAA-BBBBB')).toBeInTheDocument();
      expect(screen.getByText('CCCCC-DDDDD')).toBeInTheDocument();

      // Done is disabled until the admin explicitly acknowledges having saved the codes --
      // these are never shown again after this screen.
      const doneButton = screen.getByRole('button', { name: 'Done' });
      expect(doneButton).toBeDisabled();
      await user.click(screen.getByRole('checkbox'));
      expect(doneButton).toBeEnabled();

      await user.click(doneButton);
      await waitFor(() => expect(screen.getByText(/turned on for your account/i)).toBeInTheDocument());
    });

    it('shows an inline error and lets the admin retry on a wrong confirmation code', async () => {
      const user = userEvent.setup();
      vi.mocked(adminMfaApi.enroll).mockResolvedValue({ secret: 'SECRET123', provisioningUri: 'otpauth://totp/x' });
      vi.mocked(adminMfaApi.confirm).mockRejectedValue(apiError(AUTH_MFA_INVALID_CODE));

      renderSection();
      await user.click(await screen.findByRole('button', { name: /set up two-factor authentication/i }));
      await screen.findByText('SECRET123');

      await user.type(screen.getByLabelText(/code from your app/i), '000000');
      await user.click(screen.getByRole('button', { name: /confirm and turn on/i }));

      expect(await screen.findByText(/that code didn't work/i)).toBeInTheDocument();
      // Still on the scan/confirm step, not bounced to a dead end.
      expect(screen.getByLabelText(/code from your app/i)).toBeInTheDocument();
    });
  });

  describe('when already enrolled', () => {
    beforeEach(() => {
      vi.mocked(adminMfaApi.status).mockResolvedValue({ enabled: true });
    });

    it('shows the enabled state with a Disable action', async () => {
      renderSection();

      expect(await screen.findByText(/turned on for your account/i)).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /disable/i })).toBeInTheDocument();
    });

    it('disables MFA with the current password for a PASSWORD-method admin', async () => {
      const user = userEvent.setup();
      vi.mocked(userApi.get).mockResolvedValue({ phoneNumber: '+919876543705', signInMethod: 'PASSWORD' }); // synthetic-ok
      vi.mocked(adminMfaApi.disable).mockResolvedValue(undefined);

      renderSection();
      await user.click(await screen.findByRole('button', { name: /disable/i }));

      const passwordField = await screen.findByPlaceholderText('Current password');
      await user.type(passwordField, 'my-current-password');
      await user.click(screen.getByRole('button', { name: /disable two-factor authentication/i }));

      await waitFor(() => expect(adminMfaApi.disable).toHaveBeenCalledWith('my-current-password', null));
      await waitFor(() => expect(notifySuccess).toHaveBeenCalledWith('Two-factor authentication disabled.'));
    });

    it('shows a Google reauth prompt instead of a password field for a GOOGLE-method admin', async () => {
      const user = userEvent.setup();
      vi.mocked(userApi.get).mockResolvedValue({ phoneNumber: '+919876543705', signInMethod: 'GOOGLE' }); // synthetic-ok

      renderSection();
      await user.click(await screen.findByRole('button', { name: /disable/i }));

      // VITE_GOOGLE_LOGIN_CLIENT_ID is unset in this test environment, so GoogleReauthPrompt
      // shows its own explicit "unavailable" message rather than silently rendering nothing --
      // the point of this test is that the PASSWORD-only field is never offered to this account.
      expect(await screen.findByText(/isn't available right now/i)).toBeInTheDocument();
      expect(screen.queryByPlaceholderText('Current password')).not.toBeInTheDocument();
    });

    it('shows an error when the current credential is rejected', async () => {
      const user = userEvent.setup();
      vi.mocked(adminMfaApi.disable).mockRejectedValue({ response: { data: { message: 'Current credential could not be verified.' } } });

      renderSection();
      await user.click(await screen.findByRole('button', { name: /disable/i }));

      const passwordField = await screen.findByPlaceholderText('Current password');
      await user.type(passwordField, 'wrong-password');
      await user.click(screen.getByRole('button', { name: /disable two-factor authentication/i }));

      expect(await screen.findByText('Current credential could not be verified.')).toBeInTheDocument();
      expect(notifySuccess).not.toHaveBeenCalled();
    });

    it('cancels the disable flow without calling the API', async () => {
      const user = userEvent.setup();
      renderSection();
      await user.click(await screen.findByRole('button', { name: /disable/i }));
      await screen.findByPlaceholderText('Current password');

      await user.click(screen.getByRole('button', { name: 'Cancel' }));

      expect(screen.queryByPlaceholderText('Current password')).not.toBeInTheDocument();
      expect(adminMfaApi.disable).not.toHaveBeenCalled();
    });
  });
});
