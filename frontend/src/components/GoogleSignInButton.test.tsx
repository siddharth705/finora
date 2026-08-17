import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, waitFor } from '@testing-library/react';
import { GoogleSignInButton } from './GoogleSignInButton';
import { isGoogleLoginConfigured, loadGoogleIdentityServices } from '../lib/googleIdentity';

/**
 * Mocks lib/googleIdentity.ts wholesale rather than simulating a real <script> load -- that
 * module's own script-injection/caching/failure mechanics already have dedicated coverage in
 * googleIdentity.test.ts (including the module-scope caching that makes DOM-event simulation
 * order-sensitive across tests). This file's job is narrower: given whatever
 * loadGoogleIdentityServices resolves or rejects with, does the component wire it up correctly.
 */
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

describe('GoogleSignInButton', () => {
  it('renders nothing and never loads the script when unconfigured', () => {
    vi.mocked(isGoogleLoginConfigured).mockReturnValue(false);

    const { container } = render(
      <GoogleSignInButton text="signin_with" onCredential={vi.fn()} onError={vi.fn()} />
    );

    expect(container).toBeEmptyDOMElement();
    expect(loadGoogleIdentityServices).not.toHaveBeenCalled();
  });

  it('initializes GIS with the configured client id and renders the button', async () => {
    vi.stubEnv('VITE_GOOGLE_LOGIN_CLIENT_ID', 'test-client-id.apps.googleusercontent.com');
    vi.mocked(isGoogleLoginConfigured).mockReturnValue(true);
    const initialize = vi.fn();
    const renderButton = vi.fn();
    vi.mocked(loadGoogleIdentityServices).mockResolvedValue({ initialize, renderButton } as any);

    render(<GoogleSignInButton text="signup_with" onCredential={vi.fn()} onError={vi.fn()} />);

    await waitFor(() => expect(initialize).toHaveBeenCalledWith(
      expect.objectContaining({ client_id: 'test-client-id.apps.googleusercontent.com' })
    ));
    expect(renderButton).toHaveBeenCalledWith(expect.any(HTMLElement), expect.objectContaining({ text: 'signup_with' }));
  });

  it('hands the credential straight to onCredential when Google calls back', async () => {
    vi.stubEnv('VITE_GOOGLE_LOGIN_CLIENT_ID', 'test-client-id.apps.googleusercontent.com');
    vi.mocked(isGoogleLoginConfigured).mockReturnValue(true);
    const onCredential = vi.fn();
    const initialize = vi.fn();
    vi.mocked(loadGoogleIdentityServices).mockResolvedValue({ initialize, renderButton: vi.fn() } as any);

    render(<GoogleSignInButton text="signin_with" onCredential={onCredential} onError={vi.fn()} />);
    await waitFor(() => expect(initialize).toHaveBeenCalled());

    // Simulate Google's own button invoking the callback it was configured with.
    const { callback } = initialize.mock.calls[0][0];
    callback({ credential: 'a-real-looking-jwt' });

    expect(onCredential).toHaveBeenCalledWith('a-real-looking-jwt');
  });

  it('reports onError when Google Identity Services fails to load', async () => {
    vi.stubEnv('VITE_GOOGLE_LOGIN_CLIENT_ID', 'test-client-id.apps.googleusercontent.com');
    vi.mocked(isGoogleLoginConfigured).mockReturnValue(true);
    vi.mocked(loadGoogleIdentityServices).mockRejectedValue(new Error('Failed to load Google Identity Services.'));
    const onError = vi.fn();

    render(<GoogleSignInButton text="signin_with" onCredential={vi.fn()} onError={onError} />);

    await waitFor(() => expect(onError).toHaveBeenCalledWith(
      'Sign in with Google is unavailable right now. Please try again later.'
    ));
  });
});
