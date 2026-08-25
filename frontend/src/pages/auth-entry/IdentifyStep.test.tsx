import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { IdentifyStep } from './IdentifyStep';
import { AuthProvider } from '../../context/AuthContext';
import { authApi } from '../../api/endpoints';
import { isGoogleLoginConfigured, loadGoogleIdentityServices } from '../../lib/googleIdentity';

vi.mock('../../api/endpoints', () => ({
  authApi: { identify: vi.fn(), google: vi.fn(), apple: vi.fn(), logout: vi.fn() },
  userApi: { get: vi.fn(), update: vi.fn() },
}));

// Same wholesale mock GoogleSignInButton.test.tsx uses -- that file already covers the GIS
// script-loading/init mechanics in isolation. Mocking it here too lets this file test its own new
// responsibility: does a Google credential, once handed back, actually drive loginWithGoogle and
// onSuccess the way handleGoogleCredential is supposed to.
vi.mock('../../lib/googleIdentity', () => ({
  isGoogleLoginConfigured: vi.fn(),
  loadGoogleIdentityServices: vi.fn(),
}));

afterEach(() => {
  vi.unstubAllEnvs();
});

// No global beforeEach reset/clear here -- every test sets its own mock behavior explicitly, and
// a shared mockReset()/mockClear() running before a test whose mock throws asynchronously trips
// vitest 4's unhandled-rejection tracking (observed directly: removing it is what fixes the last
// test below). The one test that needs a clean call-count slate (checking
// authApi.identify was never called) clears it locally instead.

// useAuth() (needed for loginWithGoogle/loginWithApple) requires an AuthProvider ancestor -- same
// wrapper PasswordStep.test.tsx and RegisterStep.test.tsx already use for the same reason.
function renderStep(props: Partial<Parameters<typeof IdentifyStep>[0]> = {}) {
  const onExists = vi.fn();
  const onContinue = vi.fn();
  const onSuccess = vi.fn();
  render(
    <AuthProvider>
      <IdentifyStep onExists={onExists} onContinue={onContinue} onSuccess={onSuccess} {...props} />
    </AuthProvider>
  );
  return { onExists, onContinue, onSuccess };
}

describe('IdentifyStep', () => {
  it('calls onExists with the trimmed identifier when nextAction is EXISTS', async () => {
    vi.mocked(authApi.identify).mockResolvedValue({ nextAction: 'EXISTS' });
    const { onExists } = renderStep();

    await userEvent.type(screen.getByLabelText('Email or mobile number'), '  jane@example.com  ');
    await userEvent.click(screen.getByRole('button', { name: /continue/i }));

    await waitFor(() => expect(onExists).toHaveBeenCalledWith('jane@example.com'));
  });

  it('calls onContinue with an email prefill when nextAction is CONTINUE and the identifier looks like an email', async () => {
    vi.mocked(authApi.identify).mockResolvedValue({ nextAction: 'CONTINUE' });
    const { onContinue } = renderStep();

    await userEvent.type(screen.getByLabelText('Email or mobile number'), 'new@example.com');
    await userEvent.click(screen.getByRole('button', { name: /continue/i }));

    await waitFor(() => expect(onContinue).toHaveBeenCalledWith('new@example.com', { email: 'new@example.com' }));
  });

  it('calls onContinue with a phoneNumber prefill when the identifier looks like a phone number', async () => {
    vi.mocked(authApi.identify).mockResolvedValue({ nextAction: 'CONTINUE' });
    const { onContinue } = renderStep();

    await userEvent.type(screen.getByLabelText('Email or mobile number'), '+919876500011'); // synthetic-ok: fake sequential example number
    await userEvent.click(screen.getByRole('button', { name: /continue/i }));

    await waitFor(() => expect(onContinue).toHaveBeenCalledWith('+919876500011', { phoneNumber: '+919876500011' })); // synthetic-ok
  });

  it('shows an error and calls neither callback when the identifier is blank', async () => {
    vi.mocked(authApi.identify).mockClear(); // clean call count for the assertion below
    const { onExists, onContinue } = renderStep();

    await userEvent.click(screen.getByRole('button', { name: /continue/i }));

    expect(await screen.findByText('Enter your email or mobile number.')).toBeInTheDocument();
    expect(onExists).not.toHaveBeenCalled();
    expect(onContinue).not.toHaveBeenCalled();
    expect(authApi.identify).not.toHaveBeenCalled();
  });

  it('shows the backend error message and does not call either callback when identify() rejects', async () => {
    // Deliberately not mockRejectedValue -- that constructs the rejected Promise eagerly at mock
    // setup time, which vitest 4's stricter unhandled-rejection detection can flag as an uncaught
    // error before the component ever calls and awaits it. An async throw only creates the
    // rejection when actually invoked.
    vi.mocked(authApi.identify).mockImplementation(async () => {
      throw Object.assign(new Error('Too many attempts, try again later.'), {
        response: { data: { message: 'Too many attempts, try again later.' } },
      });
    });
    const { onExists, onContinue } = renderStep();

    await userEvent.type(screen.getByLabelText('Email or mobile number'), 'jane@example.com');
    await userEvent.click(screen.getByRole('button', { name: /continue/i }));

    await waitFor(() => expect(screen.getByText('Too many attempts, try again later.')).toBeInTheDocument());
    expect(onExists).not.toHaveBeenCalled();
    expect(onContinue).not.toHaveBeenCalled();
  });

  it('signs in directly with Google, without ever calling /auth/identify', async () => {
    vi.mocked(authApi.identify).mockClear(); // clean call count for the assertion below
    vi.stubEnv('VITE_GOOGLE_LOGIN_CLIENT_ID', 'test-client-id.apps.googleusercontent.com');
    vi.mocked(isGoogleLoginConfigured).mockReturnValue(true);
    const initialize = vi.fn();
    vi.mocked(loadGoogleIdentityServices).mockResolvedValue({ initialize, renderButton: vi.fn() } as any);
    vi.mocked(authApi.google).mockResolvedValue({
      data: { token: 't', refreshToken: 'r', email: 'jane@example.com', fullName: 'Jane', phoneVerified: true },
    } as any);
    const { onSuccess, onExists, onContinue } = renderStep();

    await waitFor(() => expect(initialize).toHaveBeenCalled());
    const { callback } = initialize.mock.calls[0][0];
    callback({ credential: 'a-real-looking-jwt' });

    await waitFor(() => expect(onSuccess).toHaveBeenCalledWith(true));
    expect(authApi.identify).not.toHaveBeenCalled();
    expect(onExists).not.toHaveBeenCalled();
    expect(onContinue).not.toHaveBeenCalled();
  });

  it('shows the backend error message and does not call onSuccess when Google sign-in fails', async () => {
    vi.stubEnv('VITE_GOOGLE_LOGIN_CLIENT_ID', 'test-client-id.apps.googleusercontent.com');
    vi.mocked(isGoogleLoginConfigured).mockReturnValue(true);
    const initialize = vi.fn();
    vi.mocked(loadGoogleIdentityServices).mockResolvedValue({ initialize, renderButton: vi.fn() } as any);
    vi.mocked(authApi.google).mockImplementation(async () => {
      throw Object.assign(new Error('New registrations are currently disabled.'), {
        response: { data: { message: 'New registrations are currently disabled.' } },
      });
    });
    const { onSuccess } = renderStep();

    await waitFor(() => expect(initialize).toHaveBeenCalled());
    const { callback } = initialize.mock.calls[0][0];
    callback({ credential: 'a-real-looking-jwt' });

    await waitFor(() => expect(screen.getByText('New registrations are currently disabled.')).toBeInTheDocument());
    expect(onSuccess).not.toHaveBeenCalled();
  });

  // Gap found on self-review: a deactivated account signing in via password already gets a
  // one-click reactivation prompt (PasswordStep.tsx's handleAuthError), because
  // AuthService#enforceAccountIsSignable throws AUTH_ACCOUNT_DEACTIVATED with a reactivation token
  // for ANY sign-in method, including loginWithOAuthIdentity (Google/Apple). Before this step could
  // trigger Google/Apple itself, that path was unreachable from here -- a deactivated user could
  // only reach Google/Apple via PasswordStep, which already handled it. Now that Google/Apple sign
  // in directly from IdentifyStep, the SAME deactivated-account response is reachable here too, and
  // dropping straight to a generic error message strands the user with no way to reactivate.
  it('shows the reactivation prompt, not a generic error, when Google sign-in reports a deactivated account', async () => {
    vi.stubEnv('VITE_GOOGLE_LOGIN_CLIENT_ID', 'test-client-id.apps.googleusercontent.com');
    vi.mocked(isGoogleLoginConfigured).mockReturnValue(true);
    const initialize = vi.fn();
    vi.mocked(loadGoogleIdentityServices).mockResolvedValue({ initialize, renderButton: vi.fn() } as any);
    vi.mocked(authApi.google).mockImplementation(async () => {
      throw Object.assign(new Error('This account is deactivated.'), {
        response: { data: { errorCode: 'AUTH_007', details: { reactivationToken: 'reactivate-me-token' } } },
      });
    });
    const { onSuccess } = renderStep();

    await waitFor(() => expect(initialize).toHaveBeenCalled());
    const { callback } = initialize.mock.calls[0][0];
    callback({ credential: 'a-real-looking-jwt' });

    expect(await screen.findByRole('button', { name: /reactivate my account/i })).toBeInTheDocument();
    expect(onSuccess).not.toHaveBeenCalled();
  });
});
