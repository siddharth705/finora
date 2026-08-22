import { useEffect, useState, type FormEvent } from 'react';
import { Navigate, useLocation, useNavigate, Link } from 'react-router-dom';
import { useAdminAuth, AdminAccessError } from '../context/AdminAuthContext';
import { BrandMark } from '../components/BrandMark';
import { PasswordInput } from '../components/PasswordInput';
import { setupApi } from '../api/endpoints';
import { ADMIN_SESSION_ENDED_REASON_KEY } from '../api/client';
import { AUTH_MFA_REQUIRED } from '../api/errorCodes';
import { safeStorage } from '../lib/safeStorage';

/**
 * The only two post-login destinations that exist today. Deliberately a plain function over a
 * string union rather than a richer AuthState/NextStep type -- there's no third *routable*
 * outcome yet to justify one (a failed login throws and is handled separately in handleSubmit's
 * catch; that's an error path, not a destination, so it doesn't belong in this function's
 * return type). Revisit this once a second verification factor (TOTP, etc.) actually exists --
 * see docs/adr/0001-administrator-verification-strategy.md -- at which point a real NextStep
 * union (dashboard | verify-phone | verify-totp | enroll-totp | ...) earns its keep.
 */
function nextRouteFor(phoneVerified: boolean): '/' | '/verify-phone' {
  return phoneVerified ? '/' : '/verify-phone';
}

export default function Login() {
  const { token, phoneVerified, login, completeMfaChallenge } = useAdminAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [identifier, setIdentifier] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [setupRequired, setSetupRequired] = useState(false);
  const [checkingSetup, setCheckingSetup] = useState(true);
  // Set the moment login() comes back AUTH_MFA_REQUIRED (password was correct; the account has
  // MFA enabled) -- its presence, not a separate `step` enum, is what switches the form below
  // into the code-entry step, since there's nothing else this page can be showing once it's set.
  const [mfaChallengeToken, setMfaChallengeToken] = useState<string | null>(null);
  const [mfaCode, setMfaCode] = useState('');
  const [mfaError, setMfaError] = useState<string | null>(null);
  // A one-time confirmation from ResetPassword's own post-success redirect ("Password reset
  // successfully..."). This page previously never read location.state at all, so ResetPassword.tsx
  // passing this message did nothing -- an admin who reset their password landed here with no
  // acknowledgment it worked. Mirrors the user app's Login.tsx `banner` exactly, including reading
  // it once on mount rather than reactively, so it can't reappear after being dismissed.
  const [banner] = useState<string | null>(() => (location.state as { message?: string } | null)?.message ?? null);
  // Why the admin is looking at this screen when they didn't ask to be -- stashed by
  // endSessionAndRedirect() in api/client.ts, whose full-page navigation unmounts React and takes
  // any in-memory state with it. Read once into state, deleted immediately (see the effect below)
  // so it can't resurface on a later visit. Mirrors the user app's Login.tsx exactly.
  const [sessionEndedReason] = useState<string | null>(
    () => safeStorage.getItem(ADMIN_SESSION_ENDED_REASON_KEY));

  useEffect(() => {
    // Checked once, unauthenticated -- lets a fresh install land on /setup automatically instead
    // of requiring anyone to already know that URL exists. Failure (backend unreachable, etc.)
    // is treated as "not required" so this never traps the normal login form from rendering.
    // checkingSetup gates the form itself (not just this redirect) so there's no window where a
    // fast or automated submission could reach the real login attempt before this resolves.
    setupApi.status()
      .then((status) => setSetupRequired(status.setupRequired))
      .catch(() => {})
      .finally(() => setCheckingSetup(false));
    // Consumed on read: it lives in storage, so without this it would outlive not just this render
    // but the whole tab, and reappear on an unrelated future visit to the login screen.
    if (safeStorage.getItem(ADMIN_SESSION_ENDED_REASON_KEY)) {
      safeStorage.removeItem(ADMIN_SESSION_ENDED_REASON_KEY);
    }
    // history.state otherwise survives a manual page refresh (unlike the in-memory banner state
    // above), which would re-show a stale "password reset" confirmation on an unrelated future
    // visit to this same history entry.
    if (banner) void navigate(location.pathname, { replace: true });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (setupRequired) return <Navigate to="/setup" replace />;

  // Already signed in (e.g. hit /login directly with a valid session) -- bounce straight to the
  // dashboard, UNLESS verification is still pending, in which case that's genuinely where this
  // session needs to go next rather than a dashboard it can't actually load permissions for.
  if (token) return <Navigate to={nextRouteFor(phoneVerified)} replace />;

  if (checkingSetup) {
    return (
      <div className="min-h-screen bg-bg flex items-center justify-center">
        <p className="text-sm text-muted">Loading…</p>
      </div>
    );
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      const verified = await login(identifier, password);
      void navigate(nextRouteFor(verified));
    } catch (err: any) {
      // AUTH_MFA_REQUIRED: the password was correct, but this account has MFA enabled -- not a
      // failure to display, a second step to start. The challenge token travels in `details`
      // (see client.ts's interceptor and AdminAuthContext.login()'s own comment on why).
      if (err instanceof AdminAccessError && err.code === AUTH_MFA_REQUIRED
          && typeof err.details?.mfaChallengeToken === 'string') {
        setMfaChallengeToken(err.details.mfaChallengeToken);
      } else {
        setError(err?.message ?? 'Sign in failed. Check your credentials and try again.');
      }
    } finally {
      setSubmitting(false);
    }
  }

  function cancelMfaChallenge() {
    setMfaChallengeToken(null);
    setMfaCode('');
    setMfaError(null);
  }

  async function handleMfaSubmit(e: FormEvent) {
    e.preventDefault();
    if (!mfaChallengeToken || mfaCode.trim().length === 0) return;
    setMfaError(null);
    setSubmitting(true);
    try {
      const verified = await completeMfaChallenge(mfaChallengeToken, mfaCode.trim());
      void navigate(nextRouteFor(verified));
    } catch (err: any) {
      setMfaError(err?.message ?? "That code didn't work. Check your authenticator app and try again.");
    } finally {
      setSubmitting(false);
    }
  }

  if (mfaChallengeToken) {
    return (
      <div className="min-h-screen bg-bg flex items-center justify-center p-4">
        <div className="w-full max-w-sm">
          <div className="flex items-center gap-2.5 justify-center mb-8">
            <BrandMark size={36} variant="auto" className="rounded-lg flex-shrink-0" />
            <span className="font-extrabold tracking-wide text-xl text-ink">FINORA ADMIN</span>
          </div>

          <form onSubmit={handleMfaSubmit} className="bg-card border border-border rounded-xl2 shadow-soft p-6 space-y-4">
            <div>
              <p className="text-sm font-semibold text-ink mb-1">Two-factor authentication</p>
              <p className="text-xs text-muted">
                Enter the 6-digit code from your authenticator app, or one of your recovery codes.
              </p>
            </div>
            <div>
              <label htmlFor="login-mfa-code" className="block text-sm font-medium text-ink mb-1.5">Code</label>
              <input
                id="login-mfa-code"
                type="text"
                inputMode="text"
                autoFocus
                autoComplete="one-time-code"
                required
                value={mfaCode}
                onChange={(e) => setMfaCode(e.target.value)}
                placeholder="123456"
                className="w-full bg-bg border border-border rounded-lg px-3.5 py-2.5 text-center text-lg tracking-[0.3em] font-mono focus:outline-none focus:ring-2 focus:ring-primary/30"
              />
            </div>

            {mfaError && (
              <p className="text-sm text-danger bg-danger-bg rounded-lg px-3.5 py-2.5">{mfaError}</p>
            )}

            <button
              type="submit"
              disabled={submitting || mfaCode.trim().length === 0}
              className="w-full bg-primary hover:bg-primary-dark text-on-primary font-semibold rounded-lg py-2.5 text-sm disabled:opacity-50"
            >
              {submitting ? 'Verifying…' : 'Verify'}
            </button>
            <button
              type="button"
              onClick={cancelMfaChallenge}
              className="w-full text-muted hover:text-ink text-xs font-medium text-center"
            >
              Back to sign in
            </button>
          </form>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-bg flex items-center justify-center p-4">
      <div className="w-full max-w-sm">
        <div className="flex items-center gap-2.5 justify-center mb-8">
          <BrandMark size={36} variant="auto" className="rounded-lg flex-shrink-0" />
          <span className="font-extrabold tracking-wide text-xl text-ink">FINORA ADMIN</span>
        </div>

        <form onSubmit={handleSubmit} className="bg-card border border-border rounded-xl2 shadow-soft p-6 space-y-4">
          <div>
            <label htmlFor="login-identifier" className="block text-sm font-medium text-ink mb-1.5">Email or phone</label>
            <input
              id="login-identifier"
              type="text"
              required
              autoFocus
              value={identifier}
              onChange={(e) => setIdentifier(e.target.value)}
              className="w-full bg-bg border border-border rounded-lg px-3.5 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-primary/30"
            />
          </div>
          <div>
            <div className="flex items-center justify-between mb-1.5">
              <label htmlFor="login-password" className="block text-sm font-medium text-ink">Password</label>
              <Link to="/forgot-password" className="text-xs text-primary font-medium">Forgot password?</Link>
            </div>
            <PasswordInput
              id="login-password"
              required
              value={password}
              onChange={setPassword}
              className="w-full bg-bg border border-border rounded-lg px-3.5 py-2.5 pr-10 text-sm focus:outline-none focus:ring-2 focus:ring-primary/30"
            />
          </div>

          {banner && (
            <p className="text-sm text-success bg-success-bg rounded-lg px-3.5 py-2.5">{banner}</p>
          )}
          {/* Styled as a warning rather than an error: nothing the admin did was wrong, and it
              clears the moment they sign in again. Hidden once a real submit error arrives, so a
              stale "you were signed out" note doesn't sit above a fresh "wrong password" one. */}
          {sessionEndedReason && !error && (
            <p role="status" className="text-sm text-warning bg-warning-bg rounded-lg px-3.5 py-2.5">{sessionEndedReason}</p>
          )}
          {error && (
            <p className="text-sm text-danger bg-danger-bg rounded-lg px-3.5 py-2.5">{error}</p>
          )}

          <button
            type="submit"
            disabled={submitting}
            className="w-full bg-primary hover:bg-primary-dark text-on-primary font-semibold rounded-lg py-2.5 text-sm disabled:opacity-50"
          >
            {submitting ? 'Signing in…' : 'Sign in'}
          </button>
        </form>

        <p className="text-center text-xs text-muted mt-6">
          This portal is for accounts with admin permissions only. Regular users should use the
          main Finora app.
        </p>
      </div>
    </div>
  );
}
