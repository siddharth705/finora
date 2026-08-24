import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ShieldCheck, ArrowRight, User } from 'lucide-react';
import { BrandMark } from '../components/BrandMark';
import { GoogleSignInButton } from '../components/GoogleSignInButton';
import { AppleSignInButton } from '../components/AppleSignInButton';
import { authApi } from '../api/endpoints';
import { useAuth } from '../context/AuthContext';
import { AUTH_ACCOUNT_DEACTIVATED } from '../api/errorCodes';
import { ReactivateAccountPrompt } from '../components/ReactivateAccountPrompt';

// Matches Register.tsx's own EMAIL_PATTERN -- used here only to decide which of Register's two
// fields (email vs mobile number) to prefill when nextAction is CONTINUE, not as a submission
// gate (the backend is the one source of truth for what counts as a valid identifier).
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/**
 * Unified authentication entry page (auth/security review §2.2 / Phase 3). A single identifier
 * field replaces having to pick "Login" vs "Register" up front -- POST /auth/identify (see
 * AuthService.identify) resolves it to what should happen next, and this page routes there:
 *
 * - EXISTS -- an account exists for this identifier (any sign-in method). Sent to /login with
 *   the identifier prefilled; the password field and Google button are always shown together
 *   there, same as a direct visit -- see Phase 7's amendment below for why this no longer
 *   branches on which method the account actually uses.
 * - CONTINUE -- no account behind this identifier (or at least, nothing this endpoint will
 *   confirm -- see AuthService.identify's own doc comment on why status isn't surfaced here).
 *   Sent to /register with whichever of its two fields (email or mobile number) the identifier
 *   looks like, prefilled.
 *
 * /login and /register stay fully live and directly reachable on their own -- this page fronts
 * them, it doesn't replace or gate them. Landing-page CTA wiring (whether "Sign in" / "Get
 * started" should route through here instead of straight to /login /register) is left for a
 * separate decision, not part of this slice.
 *
 * Phase 7 amendment (resolved 2026-08-23): nextAction used to be PASSWORD/GOOGLE/APPLE/CONTINUE,
 * and this page forwarded the method to Login.tsx so it could hide the password field for a
 * known OAuth account (§2.4's "move the OAuth-user rejection earlier"). Collapsed to EXISTS/
 * CONTINUE to stop /auth/identify revealing which sign-in method an existing account uses --
 * closing that half of the enumeration leak cost this page its per-method routing, which is the
 * accepted tradeoff (see IdentifyResponse's own doc comment on the backend for the full
 * reasoning). The backend's own signInMethod refusal at actual login time is unaffected.
 */
export default function AuthEntry() {
  const { loginWithGoogle, loginWithApple } = useAuth();
  const navigate = useNavigate();
  const [identifier, setIdentifier] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [reactivationToken, setReactivationToken] = useState<string | null>(null);

  const identifierValid = identifier.trim().length > 0;

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
        void navigate('/register', { state: isEmail ? { email: trimmed } : { phoneNumber: trimmed } });
      } else {
        void navigate('/login', { state: { identifier: trimmed } });
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
      const phoneVerified = await loginWithGoogle(idToken);
      void navigate(phoneVerified ? '/app' : '/verify-phone', { state: phoneVerified ? undefined : { fromLogin: true } });
    } catch (err: any) {
      const token = err.response?.data?.errorCode === AUTH_ACCOUNT_DEACTIVATED
        ? err.response?.data?.details?.reactivationToken
        : null;
      if (token) {
        setReactivationToken(token);
      } else {
        setError(err.response?.data?.message ?? 'Google sign-in failed.');
      }
    } finally {
      setLoading(false);
    }
  }

  async function handleAppleCredential(idToken: string, fullName: string | null) {
    setError(null);
    setLoading(true);
    try {
      const phoneVerified = await loginWithApple(idToken, fullName);
      void navigate(phoneVerified ? '/app' : '/verify-phone', { state: phoneVerified ? undefined : { fromLogin: true } });
    } catch (err: any) {
      const token = err.response?.data?.errorCode === AUTH_ACCOUNT_DEACTIVATED
        ? err.response?.data?.details?.reactivationToken
        : null;
      if (token) {
        setReactivationToken(token);
      } else {
        setError(err.response?.data?.message ?? 'Apple sign-in failed.');
      }
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="min-h-screen bg-bg flex flex-col items-center justify-center p-4 gap-6">
      <div className="w-full max-w-sm">
        {reactivationToken ? (
          <ReactivateAccountPrompt
            token={reactivationToken}
            onCancel={() => setReactivationToken(null)}
            onReactivated={(phoneVerified) =>
              void navigate(phoneVerified ? '/app' : '/verify-phone', { state: phoneVerified ? undefined : { fromLogin: true } })
            }
          />
        ) : (
        <form onSubmit={handleSubmit} noValidate className="bg-card rounded-xl2 p-8 w-full shadow-soft border border-border">
          <div className="flex items-center gap-2 mb-6">
            <Link to="/" className="flex items-center gap-2 w-fit">
              <BrandMark size={28} variant="auto" className="rounded-lg" />
              <span className="font-extrabold tracking-wide text-ink">FYNORA</span>
            </Link>
          </div>

          <h2 className="text-2xl font-extrabold text-ink mb-1">Sign in or create an account</h2>
          <p className="text-sm text-muted mb-6">Enter your email or mobile number to continue</p>

          {error && <p className="text-danger text-sm mb-4">{error}</p>}

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
            className="w-full bg-primary hover:bg-primary-dark text-on-primary rounded-lg py-2.5 text-sm font-semibold flex items-center justify-center gap-1.5 disabled:opacity-50"
          >
            {loading ? 'Continuing…' : 'Continue'}
            {!loading && <ArrowRight size={15} />}
          </button>

          <div className="flex items-center gap-3 my-5">
            <div className="flex-1 h-px bg-border" />
            <span className="text-xs text-muted">or continue with</span>
            <div className="flex-1 h-px bg-border" />
          </div>

          <GoogleSignInButton text="signin_with" onCredential={handleGoogleCredential} onError={setError} />
          <div className="mt-3">
            <AppleSignInButton onCredential={handleAppleCredential} onError={setError} />
          </div>

          <div className="flex items-start gap-2.5 bg-primary-light rounded-lg p-3 mt-6">
            <ShieldCheck size={16} className="text-primary flex-shrink-0 mt-0.5" />
            <p className="text-xs text-ink">Your financial data is encrypted and securely protected.</p>
          </div>
        </form>
        )}
      </div>

      <p className="text-xs text-muted flex items-center gap-2">
        <ShieldCheck size={13} /> Bank-grade encryption. Your data is never sold.
      </p>
    </div>
  );
}
