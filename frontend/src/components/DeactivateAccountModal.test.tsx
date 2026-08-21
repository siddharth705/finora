import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { DeactivateAccountModal } from './DeactivateAccountModal';
import { accountLifecycleApi } from '../api/endpoints';
import { isGoogleLoginConfigured, loadGoogleIdentityServices } from '../lib/googleIdentity';

vi.mock('../api/endpoints', () => ({
  accountLifecycleApi: { deactivate: vi.fn() },
}));

// See GoogleSignInButton.test.tsx's own doc comment for why this is mocked wholesale.
vi.mock('../lib/googleIdentity', () => ({
  isGoogleLoginConfigured: vi.fn(),
  loadGoogleIdentityServices: vi.fn(),
}));

describe('DeactivateAccountModal', () => {
  beforeEach(() => {
    vi.mocked(accountLifecycleApi.deactivate).mockReset().mockResolvedValue({ message: 'Deactivated.' });
  });

  describe('a password account', () => {
    it('deactivates with the entered password and reason', async () => {
      const user = userEvent.setup();
      const onDeactivated = vi.fn();
      render(<DeactivateAccountModal onClose={vi.fn()} onDeactivated={onDeactivated} signInMethod="PASSWORD" />);

      await user.type(screen.getByLabelText(/current password/i), 'OldPass123!');
      await user.selectOptions(screen.getByLabelText(/reason/i), 'TAKING_A_BREAK');
      await user.click(screen.getByRole('button', { name: /deactivate account/i }));

      await waitFor(() => expect(accountLifecycleApi.deactivate).toHaveBeenCalledWith(
        'OldPass123!', null, 'TAKING_A_BREAK', undefined
      ));
      expect(onDeactivated).toHaveBeenCalled();
    });
  });

  describe('a Google Sign-In account', () => {
    beforeEach(() => {
      vi.stubEnv('VITE_GOOGLE_LOGIN_CLIENT_ID', 'test-client-id.apps.googleusercontent.com');
      vi.mocked(isGoogleLoginConfigured).mockReturnValue(true);
      vi.mocked(loadGoogleIdentityServices).mockResolvedValue({
        initialize: vi.fn(), renderButton: vi.fn(),
      } as any);
    });

    it('offers no password field, and withholds the Google button until a reason is chosen', async () => {
      render(<DeactivateAccountModal onClose={vi.fn()} onDeactivated={vi.fn()} signInMethod="GOOGLE" />);

      expect(screen.queryByLabelText(/current password/i)).not.toBeInTheDocument();
      expect(loadGoogleIdentityServices).not.toHaveBeenCalled();
      expect(screen.getByText(/choose a reason above/i)).toBeInTheDocument();
    });

    it('deactivates with the fresh Google credential once a reason is chosen', async () => {
      const user = userEvent.setup();
      const initialize = vi.fn();
      vi.mocked(loadGoogleIdentityServices).mockResolvedValue({ initialize, renderButton: vi.fn() } as any);
      const onDeactivated = vi.fn();
      render(<DeactivateAccountModal onClose={vi.fn()} onDeactivated={onDeactivated} signInMethod="GOOGLE" />);

      await user.selectOptions(screen.getByLabelText(/reason/i), 'PRIVACY_CONCERNS');
      await waitFor(() => expect(initialize).toHaveBeenCalled());

      const { callback } = initialize.mock.calls[0][0];
      callback({ credential: 'fresh-google-id-token' });

      await waitFor(() => expect(accountLifecycleApi.deactivate).toHaveBeenCalledWith(
        null, 'fresh-google-id-token', 'PRIVACY_CONCERNS', undefined
      ));
      expect(onDeactivated).toHaveBeenCalled();
    });
  });
});
