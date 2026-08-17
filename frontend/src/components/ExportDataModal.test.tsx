import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ExportDataModal } from './ExportDataModal';
import { accountLifecycleApi } from '../api/endpoints';
import { isGoogleLoginConfigured, loadGoogleIdentityServices } from '../lib/googleIdentity';

vi.mock('../api/endpoints', () => ({
  accountLifecycleApi: { exportData: vi.fn() },
}));

// See GoogleSignInButton.test.tsx's own doc comment for why this is mocked wholesale.
vi.mock('../lib/googleIdentity', () => ({
  isGoogleLoginConfigured: vi.fn(),
  loadGoogleIdentityServices: vi.fn(),
}));

describe('ExportDataModal', () => {
  beforeEach(() => {
    vi.mocked(accountLifecycleApi.exportData).mockReset().mockResolvedValue(undefined);
  });

  describe('a password account', () => {
    it('requests the export with the entered password', async () => {
      const user = userEvent.setup();
      const onClose = vi.fn();
      render(<ExportDataModal onClose={onClose} signInMethod="PASSWORD" />);

      await user.type(screen.getByLabelText(/current password/i), 'OldPass123!');
      await user.click(screen.getByRole('button', { name: /export my data/i }));

      await waitFor(() => expect(accountLifecycleApi.exportData).toHaveBeenCalledWith('OldPass123!', null));
      expect(onClose).toHaveBeenCalled();
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

    it('offers no password field, and no separate submit button', async () => {
      render(<ExportDataModal onClose={vi.fn()} signInMethod="GOOGLE" />);

      await waitFor(() => expect(loadGoogleIdentityServices).toHaveBeenCalled());
      expect(screen.queryByLabelText(/current password/i)).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /export my data/i })).not.toBeInTheDocument();
    });

    it('requests the export with the fresh Google credential instead of a password', async () => {
      const initialize = vi.fn();
      vi.mocked(loadGoogleIdentityServices).mockResolvedValue({ initialize, renderButton: vi.fn() } as any);
      const onClose = vi.fn();
      render(<ExportDataModal onClose={onClose} signInMethod="GOOGLE" />);
      await waitFor(() => expect(initialize).toHaveBeenCalled());

      const { callback } = initialize.mock.calls[0][0];
      callback({ credential: 'fresh-google-id-token' });

      await waitFor(() => expect(accountLifecycleApi.exportData).toHaveBeenCalledWith(null, 'fresh-google-id-token'));
      expect(onClose).toHaveBeenCalled();
    });
  });
});
