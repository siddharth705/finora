import { useEffect, useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import {
  ShieldCheck, UploadCloud, TrendingUp, PiggyBank, Target, LineChart,
  Wallet, PieChart as PieChartIcon, BarChart3, ArrowRight,
} from 'lucide-react';
import { useAuth } from '../context/AuthContext';
import { BrandMark } from '../components/BrandMark';
import { PasswordInput } from '../components/PasswordInput';
import { ReactivateAccountPrompt } from '../components/ReactivateAccountPrompt';
import { GoogleSignInButton } from '../components/GoogleSignInButton';
import { AppleSignInButton } from '../components/AppleSignInButton';
import { SESSION_ENDED_REASON_KEY } from '../api/client';
import { AUTH_ACCOUNT_DEACTIVATED } from '../api/errorCodes';
import { safeStorage } from '../lib/safeStorage';

// Mirrors Register.tsx's marketing panel exactly -- same feature list, same layout, same
// decorative flourish -- so the two auth screens read as one continuous product rather than
// two different apps stitched together.
const FEATURES = [
  { icon: ShieldCheck, iconBg: 'bg-blue-100', iconColor: 'text-blue-600', title: 'Secure & Private', desc: 'Your data is encrypted and bank-level secure.' },
  { icon: UploadCloud, iconBg: 'bg-green-100', iconColor: 'text-green-600', title: 'Auto Statement Import', desc: 'Import bank & credit card statements in seconds.' },
  { icon: TrendingUp, iconBg: 'bg-orange-100', iconColor: 'text-orange-600', title: 'AI Financial Insights', desc: 'AI-powered insights to help you save more.' },
  { icon: PiggyBank, iconBg: 'bg-purple-100', iconColor: 'text-purple-600', title: 'Budget Tracking', desc: 'Set budgets and stay effortlessly on track.' },
  { icon: Target, iconBg: 'bg-blue-100', iconColor: 'text-blue-600', title: 'Goal Management', desc: 'Plan and reach your financial goals faster.' },
  { icon: LineChart, iconBg: 'bg-teal-100', iconColor: 'text-teal-600', title: 'Investment Tracking', desc: 'Track your portfolio and net worth growth.' },
];

export default function Login() {
  const { login, loginWithGoogle, loginWithApple } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  // Phase 3 (§2.2): AuthEntry.tsx already resolved this identifier to an existing account via
  // POST /auth/identify -- prefill it here instead of asking the user to retype it. Read once at
  // mount (not reactively, same reasoning as `banner` below: router state shouldn't resurface on
  // an unrelated later render of this same route).
  //
  // Phase 7 (resolved 2026-08-23): this used to also carry `method` (PASSWORD/GOOGLE/APPLE) and
  // hide the password field for a known OAuth account -- removed along with nextAction no longer
  // revealing which method an account uses (see AuthEntry.tsx and IdentifyResponse's own doc
  // comments). The password field and Google button are always shown together now, same as a
  // direct visit to this page; the backend's own signInMethod refusal at actual login time is
  // unaffected either way.
  const [prefill] = useState<{ identifier?: string } | null>(
    () => location.state as { identifier?: string } | null,
  );
  const [identifier, setIdentifier] = useState(prefill?.identifier ?? '');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  // Set once login() reports AUTH_ACCOUNT_DEACTIVATED -- the password already checked out (see
  // AuthService.login()'s deactivated branch), so the rest of the form is replaced by a single
  // confirm step rather than making the user re-enter anything.
  const [reactivationToken, setReactivationToken] = useState<string | null>(null);
  // A one-time confirmation from ChangePasswordModal/ResetPassword's own post-success redirect
  // (e.g. "Password updated successfully. Please sign in using your new password.") -- captured
  // once on mount, not read reactively, so it can't reappear after being dismissed or on an
  // unrelated re-render. Purely client-side router state, never a URL param or server round-trip,
  // so it can't leak into a shared link or a server log.
  const [banner] = useState<string | null>(() => (location.state as { message?: string } | null)?.message ?? null);

  // The other way a message arrives here: api/client.ts's clearSessionAndRedirect() stashes WHY the
  // session ended, because its `window.location.href` navigation unmounts React and takes any
  // router state with it. Read once into state and deleted immediately below, so it behaves exactly
  // like the router-state banner -- one-shot, never resurfacing on a later visit.
  //
  // Held separately from `banner` rather than merged: a session ending is a warning ("you were
  // signed out"), while the router-state banner is a success confirmation ("password updated"), and
  // they render with different styling. They also can't collide -- a forced sign-out is a full page
  // load, which discards any router state that might have been in flight.
  const [sessionEndedReason] = useState<string | null>(() => safeStorage.getItem(SESSION_ENDED_REASON_KEY));

  // Clears the router state right after reading it -- history.state otherwise survives a manual
  // page refresh (unlike the in-memory banner state above), which would re-show a stale "password
  // updated" confirmation on an unrelated future visit to this same history entry. The stashed
  // sign-out reason needs the same treatment for the same reason, minus the history nuance: it
  // lives in storage, so it would otherwise outlive not just this render but the whole tab.
  useEffect(() => {
    if (banner) void navigate(location.pathname, { replace: true });
    if (sessionEndedReason) safeStorage.removeItem(SESSION_ENDED_REASON_KEY);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Deliberately no format-restricting regex here (unlike Register's phone/email fields) --
  // this single field has to accept either a full email address or a mobile number, so it
  // can't be validated against one narrow pattern. Presence + trimming is all that's enforced
  // client-side; the backend resolves whichever form was typed (see resolveEmailForLogin).
  const identifierValid = identifier.trim().length > 0;

  // Shared by handleSubmit, handleGoogleCredential and handleReactivated -- all three end the
  // same way once a session exists. fromLogin distinguishes a RETURNING user who still hasn't
  // verified from a brand-new registration landing there for the first time (see Register.tsx's
  // own identical navigate call, which never sets it) -- VerifyPhone.tsx uses it to greet the two
  // differently rather than showing "Welcome back" to someone who just signed up.
  function afterAuthSuccess(phoneVerified: boolean) {
    void navigate(phoneVerified ? '/app' : '/verify-phone', { state: phoneVerified ? undefined : { fromLogin: true } });
  }

  // Shared by handleSubmit and handleGoogleCredential -- both reach the same account-status gate
  // server-side (AuthService.enforceAccountIsSignable) and need the same reaction to a deactivated
  // account's reactivation-token response.
  function handleAuthError(err: any, fallbackMessage: string) {
    // See errorCodes.ts's own doc comment on AUTH_ACCOUNT_DEACTIVATED for why this compares
    // against a shared constant rather than a hand-typed literal here.
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
      afterAuthSuccess(await login(identifier.trim(), password));
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
      afterAuthSuccess(await loginWithGoogle(idToken));
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
      afterAuthSuccess(await loginWithApple(idToken, fullName));
    } catch (err: any) {
      handleAuthError(err, 'Apple sign-in failed.');
    } finally {
      setLoading(false);
    }
  }

  function handleReactivated(phoneVerified: boolean) {
    afterAuthSuccess(phoneVerified);
  }

  return (
    <div className="min-h-screen bg-bg flex flex-col items-center justify-center p-4 lg:p-8 gap-6">
      <div className="w-full max-w-6xl grid lg:grid-cols-2 gap-10 lg:gap-16 items-center">
        {/* Marketing panel — hidden below lg, identical to Register.tsx's so the two screens
            feel like one continuous flow rather than a redesigned page next to a stale one. */}
        <div className="hidden lg:block">
          <Link to="/" className="flex items-center gap-2.5 mb-8 w-fit">
            <BrandMark size={36} variant="auto" className="rounded-lg" />
            <span className="font-extrabold tracking-wide text-ink text-xl">FINORA</span>
          </Link>

          <span className="inline-block bg-primary-light text-primary text-xs font-medium px-3 py-1 rounded-full mb-4">
            Welcome back
          </span>
          <h1 className="text-4xl font-bold text-ink leading-tight mb-4">
            Pick up right where you <span className="text-primary">left off</span>
          </h1>
          <p className="text-muted text-base mb-8 max-w-md">
            Sign in to see your latest transactions, budgets, goals and AI-powered insights —
            all in one place.
          </p>

          <div className="space-y-5 mb-10">
            {FEATURES.map((f) => (
              <div key={f.title} className="flex items-start gap-3">
                <div className={`w-10 h-10 rounded-lg ${f.iconBg} flex items-center justify-center flex-shrink-0`}>
                  <f.icon size={18} className={f.iconColor} />
                </div>
                <div>
                  <p className="text-sm font-semibold text-ink">{f.title}</p>
                  <p className="text-xs text-muted">{f.desc}</p>
                </div>
              </div>
            ))}
          </div>

          <div className="flex items-center gap-4 opacity-70">
            <div className="w-14 h-14 rounded-2xl bg-primary-light flex items-center justify-center">
              <Wallet size={22} className="text-primary" />
            </div>
            <div className="w-14 h-14 rounded-2xl bg-green-100 flex items-center justify-center -translate-y-2">
              <PieChartIcon size={22} className="text-green-600" />
            </div>
            <div className="w-14 h-14 rounded-2xl bg-orange-100 flex items-center justify-center">
              <BarChart3 size={22} className="text-orange-600" />
            </div>
          </div>
        </div>

        {/* Sign-in card -- replaced by the reactivation prompt once login() proves the password
            was correct for a deactivated account (see handleSubmit's catch block). */}
        {reactivationToken ? (
          <ReactivateAccountPrompt
            token={reactivationToken}
            onCancel={() => setReactivationToken(null)}
            onReactivated={handleReactivated}
          />
        ) : (
        <form onSubmit={handleSubmit} noValidate className="bg-card rounded-xl2 p-8 w-full shadow-soft border border-border">
          <div className="flex items-center gap-2 mb-6 lg:hidden">
            <Link to="/" className="flex items-center gap-2 w-fit">
              <BrandMark size={28} variant="auto" className="rounded-lg" />
              <span className="font-extrabold tracking-wide text-ink">FINORA</span>
            </Link>
          </div>

          <h2 className="text-2xl font-bold text-ink mb-1">Sign in</h2>
          <p className="text-sm text-muted mb-6">Enter your details to access your account</p>

          {banner && (
            <p className="text-success text-sm bg-success-bg rounded-lg px-3 py-2 mb-4">{banner}</p>
          )}
          {/* Why the user is looking at this screen when they didn't ask to be -- see
              SESSION_ENDED_REASON_KEY in api/client.ts. Styled as a warning, not an error: nothing
              the user did was wrong, and it clears the moment they sign in again. Hidden once they
              submit and get a real error back, so the stale "you were signed out" note doesn't sit
              above a fresh "wrong password" one. */}
          {sessionEndedReason && !error && (
            <p role="status" className="text-warning text-sm bg-warning-bg rounded-lg px-3 py-2 mb-4">{sessionEndedReason}</p>
          )}
          {error && <p className="text-danger text-sm mb-4">{error}</p>}

          {/* Accepts either identifier -- users shouldn't have to remember which one they
              registered with. See AuthService.resolveEmailForLogin on the backend. */}
          <label htmlFor="login-identifier" className="block text-xs font-medium text-muted mb-1">Email or mobile number</label>
          <input
            id="login-identifier"
            type="text"
            required
            autoComplete="username"
            value={identifier}
            onChange={(e) => setIdentifier(e.target.value)}
            placeholder="you@example.com or +91XXXXXXXXXX"
            className="w-full border border-border rounded-lg px-3 py-2.5 mb-4 text-sm bg-white text-gray-900 focus:outline-none focus:ring-2 focus:ring-primary/30"
          />

          <label htmlFor="login-password" className="block text-xs font-medium text-muted mb-1">Password</label>
          <PasswordInput
            id="login-password"
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
            className="w-full bg-primary hover:bg-primary-dark text-on-primary rounded-lg py-2.5 text-sm font-semibold flex items-center justify-center gap-1.5 disabled:opacity-50"
          >
            {loading ? 'Signing in…' : 'Sign in'}
            {!loading && <ArrowRight size={15} />}
          </button>

          <div className="flex items-center gap-3 my-5">
            <div className="flex-1 h-px bg-border" />
            <span className="text-xs text-muted">OR</span>
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

          <p className="text-sm mt-4 text-center text-muted">
            No account? <Link to="/register" className="text-primary font-medium">Register</Link>
          </p>
        </form>
        )}
      </div>

      <p className="text-xs text-muted flex items-center gap-2">
        <ShieldCheck size={13} /> Bank-grade encryption. Your data is never sold.
      </p>
    </div>
  );
}
