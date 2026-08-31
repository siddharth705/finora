import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, waitFor, fireEvent } from '@testing-library/react';
import { AppleSignInButton } from './AppleSignInButton';
import { isAppleLoginConfigured, loadAppleIdServices } from '../lib/appleIdentity';

// Mocks lib/appleIdentity.ts wholesale for the same reason GoogleSignInButton.test.tsx mocks
// lib/googleIdentity.ts -- script-injection/caching/failure mechanics are already covered in
// appleIdentity.test.ts. This file's job is narrower: given whatever loadAppleIdServices resolves
// or rejects with, does the component wire it up correctly.
vi.mock('../lib/appleIdentity', () => ({
  isAppleLoginConfigured: vi.fn(),
  loadAppleIdServices: vi.fn(),
}));

beforeEach(() => {
  vi.mocked(isAppleLoginConfigured).mockReset();
  vi.mocked(loadAppleIdServices).mockReset();
});

afterEach(() => {
  vi.unstubAllEnvs();
});

describe('AppleSignInButton', () => {
  it('renders nothing and never loads the script when unconfigured', () => {
    vi.mocked(isAppleLoginConfigured).mockReturnValue(false);

    const { container } = render(<AppleSignInButton onCredential={vi.fn()} onError={vi.fn()} />);

    expect(container).toBeEmptyDOMElement();
    expect(loadAppleIdServices).not.toHaveBeenCalled();
  });

  it('renders a visible button when configured', () => {
    vi.mocked(isAppleLoginConfigured).mockReturnValue(true);

    const { getByRole } = render(<AppleSignInButton onCredential={vi.fn()} onError={vi.fn()} />);

    expect(getByRole('button', { name: /sign in with apple/i })).toBeInTheDocument();
  });

  it('initializes with the configured client id/redirect URI and hands the id_token to onCredential', async () => {
    vi.stubEnv('VITE_APPLE_LOGIN_CLIENT_ID', 'com.finora.web');
    vi.stubEnv('VITE_APPLE_LOGIN_REDIRECT_URI', 'https://finora.app/auth');
    vi.mocked(isAppleLoginConfigured).mockReturnValue(true);
    const init = vi.fn();
    const signIn = vi.fn().mockResolvedValue({
      authorization: { id_token: 'a-real-looking-jwt', code: 'c' },
      user: { name: { firstName: 'Ada', lastName: 'Lovelace' } },
    });
    vi.mocked(loadAppleIdServices).mockResolvedValue({ init, signIn } as any);
    const onCredential = vi.fn();

    const { getByRole } = render(<AppleSignInButton onCredential={onCredential} onError={vi.fn()} />);
    fireEvent.click(getByRole('button', { name: /sign in with apple/i }));

    await waitFor(() => expect(init).toHaveBeenCalledWith(
      expect.objectContaining({
        clientId: 'com.finora.web',
        redirectURI: 'https://finora.app/auth',
        usePopup: true,
      })
    ));
    // fullName is only present on Apple's FIRST authorization for this account/client id pair --
    // forwarded through so the backend can use it on account creation, same as native.
    await waitFor(() => expect(onCredential).toHaveBeenCalledWith('a-real-looking-jwt', 'Ada Lovelace'));
  });

  it('passes null for fullName when Apple omits the user object (a returning sign-in)', async () => {
    vi.stubEnv('VITE_APPLE_LOGIN_CLIENT_ID', 'com.finora.web');
    vi.stubEnv('VITE_APPLE_LOGIN_REDIRECT_URI', 'https://finora.app/auth');
    vi.mocked(isAppleLoginConfigured).mockReturnValue(true);
    const signIn = vi.fn().mockResolvedValue({ authorization: { id_token: 'a-real-looking-jwt', code: 'c' } });
    vi.mocked(loadAppleIdServices).mockResolvedValue({ init: vi.fn(), signIn } as any);
    const onCredential = vi.fn();

    const { getByRole } = render(<AppleSignInButton onCredential={onCredential} onError={vi.fn()} />);
    fireEvent.click(getByRole('button', { name: /sign in with apple/i }));

    await waitFor(() => expect(onCredential).toHaveBeenCalledWith('a-real-looking-jwt', null));
  });

  it('reports onError when Apple returns no id_token', async () => {
    vi.stubEnv('VITE_APPLE_LOGIN_CLIENT_ID', 'com.finora.web');
    vi.stubEnv('VITE_APPLE_LOGIN_REDIRECT_URI', 'https://finora.app/auth');
    vi.mocked(isAppleLoginConfigured).mockReturnValue(true);
    const signIn = vi.fn().mockResolvedValue({ authorization: {} });
    vi.mocked(loadAppleIdServices).mockResolvedValue({ init: vi.fn(), signIn } as any);
    const onError = vi.fn();

    const { getByRole } = render(<AppleSignInButton onCredential={vi.fn()} onError={onError} />);
    fireEvent.click(getByRole('button', { name: /sign in with apple/i }));

    await waitFor(() => expect(onError).toHaveBeenCalledWith(
      'Sign in with Apple did not return a credential. Please try again.'
    ));
  });

  it('silently ignores a user-cancelled popup', async () => {
    vi.stubEnv('VITE_APPLE_LOGIN_CLIENT_ID', 'com.finora.web');
    vi.stubEnv('VITE_APPLE_LOGIN_REDIRECT_URI', 'https://finora.app/auth');
    vi.mocked(isAppleLoginConfigured).mockReturnValue(true);
    const signIn = vi.fn().mockRejectedValue({ error: 'popup_closed_by_user' });
    vi.mocked(loadAppleIdServices).mockResolvedValue({ init: vi.fn(), signIn } as any);
    const onError = vi.fn();
    const onCredential = vi.fn();

    const { getByRole } = render(<AppleSignInButton onCredential={onCredential} onError={onError} />);
    fireEvent.click(getByRole('button', { name: /sign in with apple/i }));

    await waitFor(() => expect(signIn).toHaveBeenCalled());
    expect(onError).not.toHaveBeenCalled();
    expect(onCredential).not.toHaveBeenCalled();
  });

  it('reports onError when the script fails to load', async () => {
    vi.stubEnv('VITE_APPLE_LOGIN_CLIENT_ID', 'com.finora.web');
    vi.stubEnv('VITE_APPLE_LOGIN_REDIRECT_URI', 'https://finora.app/auth');
    vi.mocked(isAppleLoginConfigured).mockReturnValue(true);
    vi.mocked(loadAppleIdServices).mockRejectedValue(new Error('Failed to load Sign in with Apple JS.'));
    const onError = vi.fn();

    const { getByRole } = render(<AppleSignInButton onCredential={vi.fn()} onError={onError} />);
    fireEvent.click(getByRole('button', { name: /sign in with apple/i }));

    await waitFor(() => expect(onError).toHaveBeenCalledWith(
      'Sign in with Apple is unavailable right now. Please try again later.'
    ));
  });
});
