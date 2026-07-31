import { useEffect, useState, type FormEvent, type ReactNode } from 'react';
import { Link, Navigate, useNavigate } from 'react-router-dom';
import { Sparkles, ShieldAlert, UserPlus, ShieldCheck, Check } from 'lucide-react';
import { setupApi } from '../api/endpoints';

type Step = 'checking' | 'key' | 'create-admin' | 'done';

// A fixed internal constant, not a real decision the person installing Finora needs to make or
// even see -- see setupApi's own doc comment for why this identifier exists at all. The
// installer experience only ever asks for the one thing that's actually secret: the key.
const BOOTSTRAP_IDENTIFIER = 'BOOTSTRAP_ADMIN';

const STEPS: { key: Step; label: string; icon: typeof ShieldAlert }[] = [
  { key: 'key', label: 'Installation key', icon: ShieldAlert },
  { key: 'create-admin', label: 'Create admin', icon: UserPlus },
  { key: 'done', label: 'Done', icon: ShieldCheck },
];

function StepIndicator({ current }: { current: Step }) {
  const currentIndex = STEPS.findIndex((s) => s.key === current);
  return (
    <div className="flex items-center justify-center gap-2 mb-8">
      {STEPS.map((s, i) => {
        const isDone = i < currentIndex;
        const isActive = i === currentIndex;
        return (
          <div key={s.key} className="flex items-center gap-2">
            <div
              className={`w-7 h-7 rounded-full flex items-center justify-center flex-shrink-0 transition-colors ${
                isDone ? 'bg-success text-white' : isActive ? 'bg-primary text-white' : 'bg-card border border-border text-muted'
              }`}
              title={s.label}
            >
              {isDone ? <Check size={14} /> : <s.icon size={14} />}
            </div>
            {i < STEPS.length - 1 && (
              <div className={`w-8 h-px ${isDone ? 'bg-success' : 'bg-border'}`} />
            )}
          </div>
        );
      })}
    </div>
  );
}

function Card({ children }: { children: ReactNode }) {
  return <div className="bg-card border border-border rounded-xl2 shadow-soft p-6 space-y-4">{children}</div>;
}

/**
 * First-run platform setup (V33__bootstrap_admin.sql / BootstrapService / SetupService).
 * Reads as a guided installer, not an extension of the admin portal -- deliberately never shares
 * layout/copy with Login.tsx beyond the FINORA wordmark. Deliberately outside AdminAuthContext
 * entirely too: see setupApi's own doc comment for why the installation key's session must never
 * touch the normal admin session's localStorage keys or interceptors. This page holds that token
 * only in its own component state, for exactly as long as it takes to make the one
 * /setup/complete call.
 */
export default function Setup() {
  const navigate = useNavigate();
  const [step, setStep] = useState<Step>('checking');
  const [alreadyComplete, setAlreadyComplete] = useState(false);
  const [installationKeyAvailable, setInstallationKeyAvailable] = useState(true);

  const [installationKey, setInstallationKey] = useState('');
  const [sessionToken, setSessionToken] = useState<string | null>(null);

  const [email, setEmail] = useState('');
  const [fullName, setFullName] = useState('');
  const [phoneNumber, setPhoneNumber] = useState('');
  const [adminPassword, setAdminPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');

  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    setupApi.status()
      .then((status) => {
        if (status.setupRequired) {
          setStep('key');
          setInstallationKeyAvailable(status.installationKeyAvailable);
        } else {
          setAlreadyComplete(true);
        }
      })
      // If the status check itself fails (backend unreachable, etc.), fall back to the normal
      // login form rather than trapping the operator on a wizard that can't proceed either way.
      .catch(() => setAlreadyComplete(true));
  }, []);

  if (alreadyComplete) return <Navigate to="/login" replace />;

  async function handleKeySubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      const response = await setupApi.loginAsBootstrap(BOOTSTRAP_IDENTIFIER, installationKey);
      setSessionToken(response.token);
      setStep('create-admin');
    } catch (err: any) {
      setError(err?.response?.data?.message === 'Invalid credentials'
        ? "That key doesn't match. Double-check what was printed to your terminal and try again."
        : (err?.response?.data?.message ?? 'Something went wrong verifying that key. Please try again.'));
    } finally {
      setSubmitting(false);
    }
  }

  async function handleCreateAdmin(e: FormEvent) {
    e.preventDefault();
    if (!sessionToken) return;
    setError(null);
    if (adminPassword !== confirmPassword) {
      setError('Passwords do not match.');
      return;
    }
    setSubmitting(true);
    try {
      await setupApi.complete(sessionToken, { email, password: adminPassword, fullName, phoneNumber });
      setStep('done');
    } catch (err: any) {
      setError(err?.response?.data?.message ?? 'Could not finish setting up your account. Check the details below and try again.');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="min-h-screen bg-bg flex items-center justify-center p-4">
      <div className="w-full max-w-sm">
        <div className="flex items-center gap-2.5 justify-center mb-2">
          <div className="w-9 h-9 rounded-lg bg-gradient-to-br from-rose-400 to-primary-dark flex items-center justify-center">
            <Sparkles size={18} className="text-white" strokeWidth={2.5} />
          </div>
          <span className="font-extrabold tracking-wide text-xl text-ink">FINORA</span>
        </div>
        <p className="text-center text-sm text-muted mb-8">Let's get your platform set up.</p>

        {step === 'checking' && (
          <p className="text-center text-sm text-muted">Checking installation status…</p>
        )}

        {step !== 'checking' && <StepIndicator current={step} />}

        {step === 'key' && (
          <form onSubmit={handleKeySubmit}>
            <Card>
              <div>
                <p className="text-sm font-semibold text-ink mb-1">Enter your installation key</p>
                <p className="text-xs text-muted">A one-time key was generated when Finora first started.</p>
              </div>

              {!installationKeyAvailable && (
                <p className="text-sm text-warning bg-warning-bg rounded-lg px-3.5 py-2.5">
                  Finora couldn't find an installation key. It may not have been generated yet,
                  or has already been used elsewhere -- see below for where to look, or ask
                  whoever deployed Finora for the key.
                </p>
              )}

              <input
                type="password"
                required
                autoFocus
                placeholder="Installation key"
                value={installationKey}
                onChange={(e) => setInstallationKey(e.target.value)}
                className="w-full bg-bg border border-border rounded-lg px-3.5 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-primary/30"
              />

              {error && <p className="text-sm text-danger bg-danger-bg rounded-lg px-3.5 py-2.5">{error}</p>}

              <button
                type="submit"
                disabled={submitting}
                className="w-full bg-primary hover:bg-primary-dark text-white font-semibold rounded-lg py-2.5 text-sm disabled:opacity-50"
              >
                {submitting ? 'Verifying…' : 'Continue'}
              </button>

              <details className="text-xs text-muted">
                <summary className="cursor-pointer font-medium text-ink select-none">Where do I find it?</summary>
                <p className="mt-2 leading-relaxed">
                  For a local installation, Finora writes it to a file named{' '}
                  <code className="bg-bg px-1 py-0.5 rounded">installation.key</code> inside a{' '}
                  <code className="bg-bg px-1 py-0.5 rounded">.finora</code> folder in your project
                  directory. If that file isn't there, the key was printed to Finora's own startup
                  output instead -- look for a message starting with "FINORA FIRST-RUN SETUP."
                </p>
              </details>
            </Card>
          </form>
        )}

        {step === 'create-admin' && (
          <form onSubmit={handleCreateAdmin}>
            <Card>
              <div>
                <p className="text-sm font-semibold text-ink mb-1">Create your administrator account</p>
                <p className="text-xs text-muted">This is the account you'll use to sign in from now on.</p>
              </div>
              <input
                type="text"
                required
                autoFocus
                placeholder="Full name"
                value={fullName}
                onChange={(e) => setFullName(e.target.value)}
                className="w-full bg-bg border border-border rounded-lg px-3.5 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-primary/30"
              />
              <input
                type="email"
                required
                placeholder="Email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                className="w-full bg-bg border border-border rounded-lg px-3.5 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-primary/30"
              />
              <input
                type="tel"
                required
                placeholder="Phone number (e.g. +91XXXXXXXXXX)"
                value={phoneNumber}
                onChange={(e) => setPhoneNumber(e.target.value)}
                className="w-full bg-bg border border-border rounded-lg px-3.5 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-primary/30"
              />
              <input
                type="password"
                required
                minLength={8}
                maxLength={72}
                placeholder="Password (at least 8 characters)"
                value={adminPassword}
                onChange={(e) => setAdminPassword(e.target.value)}
                className="w-full bg-bg border border-border rounded-lg px-3.5 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-primary/30"
              />
              <input
                type="password"
                required
                placeholder="Confirm password"
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                className="w-full bg-bg border border-border rounded-lg px-3.5 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-primary/30"
              />

              {error && <p className="text-sm text-danger bg-danger-bg rounded-lg px-3.5 py-2.5">{error}</p>}

              <button
                type="submit"
                disabled={submitting}
                className="w-full bg-primary hover:bg-primary-dark text-white font-semibold rounded-lg py-2.5 text-sm disabled:opacity-50"
              >
                {submitting ? 'Creating your account…' : 'Finish setup'}
              </button>
            </Card>
          </form>
        )}

        {step === 'done' && (
          <Card>
            <div className="w-11 h-11 rounded-full bg-success-bg text-success flex items-center justify-center mx-auto">
              <ShieldCheck size={22} />
            </div>
            <div className="text-center">
              <p className="text-sm font-semibold text-ink mb-2">You're all set</p>
              <ul className="text-xs text-muted space-y-1.5 text-left inline-block">
                <li className="flex items-center gap-2"><Check size={13} className="text-success flex-shrink-0" /> Platform initialized</li>
                <li className="flex items-center gap-2"><Check size={13} className="text-success flex-shrink-0" /> Administrator account created</li>
                <li className="flex items-center gap-2"><Check size={13} className="text-success flex-shrink-0" /> Installation key retired</li>
              </ul>
            </div>
            <button
              type="button"
              onClick={() => navigate('/login')}
              className="w-full bg-primary hover:bg-primary-dark text-white font-semibold rounded-lg py-2.5 text-sm"
            >
              Continue to sign in
            </button>
          </Card>
        )}

        {step !== 'checking' && step !== 'done' && (
          <p className="text-center text-xs text-muted mt-6">
            Already set up? <Link to="/login" className="text-primary hover:underline">Go to sign in</Link>.
          </p>
        )}
      </div>
    </div>
  );
}
