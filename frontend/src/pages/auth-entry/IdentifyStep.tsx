import { useState, type FormEvent } from 'react';
import { User } from 'lucide-react';
import { authApi } from '../../api/endpoints';
import { useAuth } from '../../context/AuthContext';
import { GoogleSignInButton } from '../../components/GoogleSignInButton';
import { AppleSignInButton } from '../../components/AppleSignInButton';
import { ReactivateAccountPrompt } from '../../components/ReactivateAccountPrompt';
import { AuthDivider } from './AuthDivider';
import { AUTH_ACCOUNT_DEACTIVATED } from '../../api/errorCodes';

// Matches RegisterStep's own EMAIL_PATTERN -- used here only to decide which of Register's two
// fields (email vs mobile number) to prefill when nextAction is CONTINUE, not as a submission
// gate (the backend is the one source of truth for what counts as a valid identifier).
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

interface IdentifyStepProps {
  onExists: (identifier: string) => void;
  onContinue: (identifier: string, prefill: { email?: string; phoneNumber?: string }) => void;
  // Google/Apple don't need the /auth/identify lookup this step otherwise exists to make -- the
  // provider already knows who the user is, and loginWithGoogle/loginWithApple transparently
  // sign in an existing account or provision a new one (AuthService#loginWithOAuthIdentity does
  // both server-side, same call PasswordStep and RegisterStep already make). So this step can
  // complete auth directly rather than only ever handing off to password/register.
  onSuccess: (phoneVerified: boolean) => void;
}

export function IdentifyStep({ onExists, onContinue, onSuccess }: IdentifyStepProps) {
  const { loginWithGoogle, loginWithApple } = useAuth();
  const [identifier, setIdentifier] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [reactivationToken, setReactivationToken] = useState<string | null>(null);

  const identifierValid = identifier.trim().length > 0;

  // Same as PasswordStep's own handleAuthError -- an OAuth credential proves identity exactly like
  // a verified password does, so AuthService#enforceAccountIsSignable's AUTH_ACCOUNT_DEACTIVATED
  // response (with its one-time reactivation token) is reachable from a Google/Apple sign-in here
  // exactly as it already is from PasswordStep, now that this step can complete auth directly.
  function handleOAuthError(err: any, fallbackMessage: string) {
    const token = err.response?.data?.errorCode === AUTH_ACCOUNT_DEACTIVATED
      ? err.response?.data?.details?.reactivationToken
      : null;
    if (token) {
      setReactivationToken(token);
    } else {
      setError(err.response?.data?.message ?? fallbackMessage);
    }
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (!identifierValid) { setError('Enter your email or mobile number.'); return; }
    setLoading(true);
    try {
      const trimmed = identifier.trim();
      const { nextAction } = await authApi.identify(trimmed);
      if (nextAction === 'CONTINUE') {
        const isEmail = EMAIL_PATTERN.test(trimmed);
        onContinue(trimmed, isEmail ? { email: trimmed } : { phoneNumber: trimmed });
      } else {
        onExists(trimmed);
      }
    } catch (err: any) {
      setError(err.response?.data?.message ?? 'Something went wrong. Please try again.');
    } finally {
      setLoading(false);
    }
  }

  async function handleGoogleCredential(idToken: string) {
    setError(null);
    setLoading(true);
    try {
      onSuccess(await loginWithGoogle(idToken));
    } catch (err: any) {
      handleOAuthError(err, 'Google sign-in failed.');
    } finally {
      setLoading(false);
    }
  }

  async function handleAppleCredential(idToken: string, fullName: string | null) {
    setError(null);
    setLoading(true);
    try {
      onSuccess(await loginWithApple(idToken, fullName));
    } catch (err: any) {
      handleOAuthError(err, 'Apple sign-in failed.');
    } finally {
      setLoading(false);
    }
  }

  if (reactivationToken) {
    return (
      <ReactivateAccountPrompt
        token={reactivationToken}
        onCancel={() => setReactivationToken(null)}
        onReactivated={(phoneVerified) => onSuccess(phoneVerified)}
      />
    );
  }

  return (
    <form onSubmit={handleSubmit} noValidate>
      <h2 className="font-display text-2xl font-extrabold text-ink mb-1">Sign in or create an account</h2>
      <p className="text-sm text-muted mb-6">Enter your email or mobile number to continue</p>

      {error && <p className="text-danger text-sm mb-4">{error}</p>}

      <GoogleSignInButton text="signin_with" onCredential={handleGoogleCredential} onError={setError} />
      <div className="mt-3">
        <AppleSignInButton onCredential={handleAppleCredential} onError={setError} />
      </div>

      <AuthDivider />

      <label htmlFor="auth-entry-identifier" className="block text-xs font-medium text-muted mb-1">Email or mobile number</label>
      <div className="relative mb-6">
        <User size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-muted" />
        <input
          id="auth-entry-identifier"
          type="text"
          required
          autoComplete="username"
          value={identifier}
          onChange={(e) => setIdentifier(e.target.value)}
          placeholder="you@example.com or +91XXXXXXXXXX"
          className="w-full border border-border rounded-lg pl-9 pr-3 py-2.5 text-sm bg-white text-gray-900 focus:outline-none focus:ring-2 focus:ring-primary/30"
        />
      </div>

      <button
        type="submit"
        disabled={loading}
        className="w-full bg-primary hover:bg-primary-dark text-on-primary rounded-lg py-2.5 text-sm font-semibold disabled:opacity-50"
      >
        {loading ? 'Continuing…' : 'Continue'}
      </button>
    </form>
  );
}
