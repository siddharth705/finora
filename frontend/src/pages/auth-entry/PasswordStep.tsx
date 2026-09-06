import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { ShieldCheck, ArrowRight } from 'lucide-react';
import { useAuth } from '../../context/AuthContext';
import { PasswordInput } from '../../components/PasswordInput';
import { ReactivateAccountPrompt } from '../../components/ReactivateAccountPrompt';
import { SocialSignInButtons } from '../../components/SocialSignInButtons';
import { AuthDivider } from './AuthDivider';
import { SESSION_ENDED_REASON_KEY } from '../../api/client';
import { AUTH_ACCOUNT_DEACTIVATED } from '../../api/errorCodes';
import { safeStorage } from '../../lib/safeStorage';

interface PasswordStepProps {
  identifier: string;
  banner: string | null;
  onSuccess: (phoneVerified: boolean) => void;
  onNotYou: () => void;
}

export function PasswordStep({ identifier: initialIdentifier, banner, onSuccess, onNotYou }: PasswordStepProps) {
  const { login, loginWithGoogle, loginWithApple } = useAuth();
  // Editable, seeded from the orchestrator's identifier -- same UX as today's Login.tsx, which
  // lets the user correct a mistyped identifier without going all the way back to IDENTIFY.
  const [identifier, setIdentifier] = useState(initialIdentifier);
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [reactivationToken, setReactivationToken] = useState<string | null>(null);
  // Seeded at 420 -- Google's own real rendered button width, measured live on production (see
  // SocialSignInButtons.tsx) -- rather than the form's natural full width, so the form narrows to
  // match Google/Apple from the first paint instead of flashing full-width and then snapping
  // narrower once Google's script actually reports back. onWidthKnown corrects this if Google
  // ever renders differently.
  const [formWidth, setFormWidth] = useState(420);
  // Same one-shot-read-then-clear pattern as today's Login.tsx -- api/client.ts's forced-signout
  // stashes why the session ended because its window.location.href navigation unmounts React.
  const [sessionEndedReason] = useState<string | null>(() => {
    const reason = safeStorage.getItem(SESSION_ENDED_REASON_KEY);
    if (reason) safeStorage.removeItem(SESSION_ENDED_REASON_KEY);
    return reason;
  });

  const identifierValid = identifier.trim().length > 0;

  function handleAuthError(err: any, fallbackMessage: string) {
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
    if (password.length === 0) { setError('Enter your password.'); return; }
    setLoading(true);
    try {
      onSuccess(await login(identifier.trim(), password));
    } catch (err: any) {
      handleAuthError(err, 'Login failed. Check your credentials.');
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
      handleAuthError(err, 'Google sign-in failed.');
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
      handleAuthError(err, 'Apple sign-in failed.');
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
    <form onSubmit={handleSubmit} noValidate style={{ maxWidth: formWidth, marginInline: 'auto' }}>
      <h2 className="font-display text-2xl font-bold text-ink mb-1">Sign in</h2>
      <p className="text-sm text-muted mb-6">Enter your details to access your account</p>

      {banner && (
        <p className="text-success text-sm bg-success-bg rounded-lg px-3 py-2 mb-4">{banner}</p>
      )}
      {sessionEndedReason && !error && (
        <p role="status" className="text-warning text-sm bg-warning-bg rounded-lg px-3 py-2 mb-4">{sessionEndedReason}</p>
      )}
      {error && <p className="text-danger text-sm mb-4">{error}</p>}

      <SocialSignInButtons
        googleText="signin_with"
        onGoogleCredential={handleGoogleCredential}
        onAppleCredential={handleAppleCredential}
        onError={setError}
        onWidthKnown={setFormWidth}
      />

      <AuthDivider />

      <label htmlFor="password-step-identifier" className="block text-xs font-medium text-muted mb-1">Email or mobile number</label>
      <input
        id="password-step-identifier"
        type="text"
        required
        autoComplete="username"
        value={identifier}
        onChange={(e) => setIdentifier(e.target.value)}
        placeholder="you@example.com or +91XXXXXXXXXX"
        className="w-full border border-border rounded-lg px-3 py-2.5 mb-4 text-sm bg-white text-gray-900 focus:outline-none focus:ring-2 focus:ring-primary/30"
      />

      <label htmlFor="password-step-password" className="block text-xs font-medium text-muted mb-1">Password</label>
      <PasswordInput
        id="password-step-password"
        value={password}
        onChange={setPassword}
        required
        autoComplete="current-password"
        className="w-full border border-border rounded-lg px-3 py-2.5 pr-10 mb-2 text-sm bg-white text-gray-900 focus:outline-none focus:ring-2 focus:ring-primary/30"
      />
      <p className="text-right mb-6">
        <Link to="/forgot-password" className="text-xs text-primary font-medium">Forgot password?</Link>
      </p>

      <button
        type="submit"
        disabled={loading}
        className="w-full bg-primary hover:bg-primary-dark active:scale-[0.98] transition-transform text-on-primary rounded-lg py-2.5 text-sm font-semibold flex items-center justify-center gap-1.5 disabled:opacity-50"
      >
        {loading ? 'Signing in…' : 'Sign in'}
        {!loading && <ArrowRight size={15} />}
      </button>

      <div className="flex items-start gap-2.5 bg-primary-light rounded-lg p-3 mt-6">
        <ShieldCheck size={16} className="text-primary flex-shrink-0 mt-0.5" />
        <p className="text-xs text-ink">Your financial data is encrypted and securely protected.</p>
      </div>

      <p className="text-sm mt-4 text-center text-muted">
        <button type="button" onClick={onNotYou} className="text-primary font-medium">Not you?</button>
      </p>
    </form>
  );
}
