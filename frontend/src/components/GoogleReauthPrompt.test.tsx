import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { GoogleReauthPrompt } from './GoogleReauthPrompt';
import { isGoogleLoginConfigured, loadGoogleIdentityServices } from '../lib/googleIdentity';

// See GoogleSignInButton.test.tsx's own doc comment for why this is mocked wholesale.
vi.mock('../lib/googleIdentity', () => ({
  isGoogleLoginConfigured: vi.fn(),
  loadGoogleIdentityServices: vi.fn(),
}));

beforeEach(() => {
  vi.mocked(isGoogleLoginConfigured).mockReset();
  vi.mocked(loadGoogleIdentityServices).mockReset();
});

afterEach(() => {
  vi.unstubAllEnvs();
});

describe('GoogleReauthPrompt', () => {
  it('shows an explanatory message instead of a dead-end button when Sign in with Google is unconfigured', () => {
    vi.mocked(isGoogleLoginConfigured).mockReturnValue(false);

    render(<GoogleReauthPrompt onCredential={vi.fn()} onError={vi.fn()} />);

    expect(screen.getByText(/sign in with google isn't available/i)).toBeInTheDocument();
    expect(loadGoogleIdentityServices).not.toHaveBeenCalled();
  });

  it('renders the Google button when configured', async () => {
    vi.stubEnv('VITE_GOOGLE_LOGIN_CLIENT_ID', 'test-client-id.apps.googleusercontent.com');
    vi.mocked(isGoogleLoginConfigured).mockReturnValue(true);
    vi.mocked(loadGoogleIdentityServices).mockResolvedValue({
      initialize: vi.fn(), renderButton: vi.fn(),
    } as any);

    render(<GoogleReauthPrompt onCredential={vi.fn()} onError={vi.fn()} />);

    await waitFor(() => expect(loadGoogleIdentityServices).toHaveBeenCalled());
    expect(screen.queryByText(/isn't available/i)).not.toBeInTheDocument();
  });
});
