import { useMemo, useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import {
  ShieldCheck, UploadCloud, TrendingUp, PiggyBank, Target, LineChart,
  User, Mail, CheckCircle2, ArrowRight, Wallet, PieChart as PieChartIcon, BarChart3,
} from 'lucide-react';
import { useAuth } from '../context/AuthContext';
import logoMark from '../assets/logo-mark.png';
import { PasswordInput } from '../components/PasswordInput';

const FEATURES = [
  { icon: ShieldCheck, iconBg: 'bg-blue-100', iconColor: 'text-blue-600', title: 'Secure & Private', desc: 'Your data is encrypted and bank-level secure.' },
  { icon: UploadCloud, iconBg: 'bg-green-100', iconColor: 'text-green-600', title: 'Auto Statement Import', desc: 'Import bank & credit card statements in seconds.' },
  { icon: TrendingUp, iconBg: 'bg-orange-100', iconColor: 'text-orange-600', title: 'AI Financial Insights', desc: 'AI-powered insights to help you save more.' },
  { icon: PiggyBank, iconBg: 'bg-purple-100', iconColor: 'text-purple-600', title: 'Budget Tracking', desc: 'Set budgets and stay effortlessly on track.' },
  { icon: Target, iconBg: 'bg-blue-100', iconColor: 'text-blue-600', title: 'Goal Management', desc: 'Plan and reach your financial goals faster.' },
  { icon: LineChart, iconBg: 'bg-teal-100', iconColor: 'text-teal-600', title: 'Investment Tracking', desc: 'Track your portfolio and net worth growth.' },
];

// Simple, honest heuristic — four independent signals (length, mixed case, a digit, a symbol),
// no external library. Purely a nudge for the user, never a submission gate: the backend's own
// 8-character minimum is the actual requirement being enforced.
function passwordStrength(pw: string): { score: number; label: string; color: string } {
  let score = 0;
  if (pw.length >= 8) score++;
  if (/[a-z]/.test(pw) && /[A-Z]/.test(pw)) score++;
  if (/[0-9]/.test(pw)) score++;
  if (/[^A-Za-z0-9]/.test(pw)) score++;
  const labels = ['Too short', 'Weak', 'Fair', 'Good', 'Strong'];
  const colors = ['bg-gray-300', 'bg-danger', 'bg-warning', 'bg-blue-500', 'bg-success'];
  return { score, label: labels[score], color: colors[score] };
}

// Digits only, capped at 10 -- the country code is now a fixed "+91" prefix shown next to the
// field rather than something typed into it (see the Mobile number field below), so this only
// ever needs to sanitize the 10-digit local number itself.
function sanitizeLocalPhoneNumber(raw: string): string {
  return raw.replace(/[^0-9]/g, '').slice(0, 10);
}

// If someone pastes a full number that already includes the country code (copied as
// "+919876543210", "919876543210", or with spaces/dashes in either form), strip the leading "91"
// so the field still ends up with just the 10-digit local part instead of the country code
// eating into it. Only strips it when there'd otherwise be more than 10 digits -- a genuine
// 10-digit number that happens to start with "91" (i.e. any number starting 910-919) must NOT
// have those two digits eaten.
function sanitizePastedPhoneNumber(raw: string): string {
  const digitsOnly = raw.replace(/[^0-9]/g, '');
  const local = digitsOnly.length > 10 && digitsOnly.startsWith('91') ? digitsOnly.slice(2) : digitsOnly;
  return local.slice(0, 10);
}

// Real Indian mobile numbers always start 6-9 -- rejecting anything else at validation time
// (not input time -- see sanitizeLocalPhoneNumber, which still allows typing any digit so the
// field doesn't reject a keystroke before the person's even finished typing) catches an
// obviously-wrong number before it ever reaches the backend.
const PHONE_PATTERN = /^[6-9][0-9]{9}$/;

// Letters (including accented/Unicode letters for names outside the ASCII range), spaces,
// hyphens, apostrophes, and periods -- covers "Jean-Luc", "O'Brien", "Md. Rahman", "José" while
// still rejecting digits, symbols, and email-like input. Must start and end with a letter so
// leading/trailing spaces or punctuation don't slip through.
const FULL_NAME_PATTERN = /^[\p{L}][\p{L}\s.'-]{0,98}[\p{L}]$/u;
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export default function Register() {
  const { register } = useAuth();
  const navigate = useNavigate();
  const [fullName, setFullName] = useState('');
  const [email, setEmail] = useState('');
  const [phoneNumber, setPhoneNumber] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [agreedToTerms, setAgreedToTerms] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Set only on a 409 from register() -- the ONLY thing that status can mean here is that the
  // email or phone already belongs to an account (see AuthService.createUserRecord's two CONFLICT
  // throws), so there's no need to parse which field it was out of the message text: either way
  // the right next step is the same, a direct path to sign in instead of leaving the user to
  // notice that themselves and navigate there by hand.
  const [showContinueLogin, setShowContinueLogin] = useState(false);
  const [loading, setLoading] = useState(false);
  // Only start showing field-level errors once the user has actually left a field -- otherwise
  // every field flashes red the instant the empty form mounts, which reads as broken rather
  // than helpful.
  const [touched, setTouched] = useState<Record<string, boolean>>({});

  const trimmedName = fullName.trim();
  const fullNameValid = trimmedName.length >= 2 && FULL_NAME_PATTERN.test(trimmedName);
  const emailValid = EMAIL_PATTERN.test(email.trim());
  const phoneValid = PHONE_PATTERN.test(phoneNumber);
  const passwordLongEnough = password.length >= 8;
  const strength = useMemo(() => passwordStrength(password), [password]);
  const passwordsMatch = confirmPassword.length > 0 && confirmPassword === password;

  const formValid =
    fullNameValid && emailValid && phoneValid && passwordLongEnough && passwordsMatch && agreedToTerms;

  function markTouched(field: string) {
    setTouched((t) => ({ ...t, [field]: true }));
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setShowContinueLogin(false);
    setTouched({ fullName: true, email: true, phoneNumber: true, password: true, confirmPassword: true });

    if (!fullNameValid) { setError('Enter your full name using letters, spaces, hyphens, or apostrophes only.'); return; }
    if (!emailValid) { setError('Enter a valid email address.'); return; }
    if (!phoneValid) { setError('Enter a valid 10-digit mobile number.'); return; }
    if (!passwordLongEnough) { setError('Password must be at least 8 characters.'); return; }
    if (!passwordsMatch) { setError('Passwords do not match.'); return; }
    if (!agreedToTerms) { setError('Please agree to the Terms & Conditions to continue.'); return; }

    setLoading(true);
    try {
      // Trimmed here too (not just visually) so the account is never created with stray
      // leading/trailing whitespace baked into the name or email.
      // phoneNumber only ever holds the 10-digit local number now (see the Mobile number field
      // below) -- +91 is prepended here, once, at the actual submission boundary, rather than
      // being stored in state at all.
      const { phoneVerified } = await register(email.trim(), password, trimmedName, `+91${phoneNumber}`);
      // VerifyPhone.tsx fetches the account's own phone number itself (now that registration
      // leaves the user authenticated) rather than needing it passed through router state.
      void navigate(phoneVerified ? '/app' : '/verify-phone');
    } catch (err: any) {
      setError(err.response?.data?.message ?? 'Registration failed.');
      setShowContinueLogin(err.response?.status === 409);
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="min-h-screen bg-bg flex flex-col items-center justify-center p-4 lg:p-8 gap-6">
      <div className="w-full max-w-6xl grid lg:grid-cols-2 gap-10 lg:gap-16 items-center">
        {/* Marketing panel — hidden below lg so the registration card stays the priority on
            small screens rather than pushing it below a long feature list. */}
        <div className="hidden lg:block">
          <Link to="/" className="flex items-center gap-2.5 mb-8 w-fit">
            <span className="w-9 h-9 rounded-lg overflow-hidden flex-shrink-0">
              <img src={logoMark} alt="" className="w-full h-full object-cover" />
            </span>
            <span className="font-extrabold tracking-wide text-ink text-xl">FINORA</span>
          </Link>

          <span className="inline-block bg-primary-light text-primary text-xs font-medium px-3 py-1 rounded-full mb-4">
            Your finances, finally in one place
          </span>
          <h1 className="text-4xl font-bold text-ink leading-tight mb-4">
            Take control of your money with <span className="text-primary">Finora</span>
          </h1>
          <p className="text-muted text-base mb-8 max-w-md">
            Import statements, track spending, set budgets and get AI-powered insights to build a
            better financial future.
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

          {/* A light decorative flourish rather than a literal illustration asset — keeps the
              panel from ending abruptly after the feature list without inventing fake imagery. */}
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

        {/* Registration card */}
        <form onSubmit={handleSubmit} noValidate className="bg-card rounded-xl2 p-8 w-full shadow-soft border border-border">
          <div className="flex items-center gap-2 mb-6 lg:hidden">
            <Link to="/" className="flex items-center gap-2 w-fit">
              <span className="w-7 h-7 rounded-lg overflow-hidden flex-shrink-0">
                <img src={logoMark} alt="" className="w-full h-full object-cover" />
              </span>
              <span className="font-extrabold tracking-wide text-ink">FINORA</span>
            </Link>
          </div>

          <h2 className="text-2xl font-bold text-ink mb-1">Create your account</h2>
          <p className="text-sm text-muted mb-6">Start your journey towards financial clarity</p>

          {error && (
            <div className="mb-4">
              <p className="text-danger text-sm mb-1.5">{error}</p>
              {showContinueLogin && (
                <Link
                  to="/login"
                  state={{ message: 'Welcome back — sign in with your existing account below.' }}
                  className="text-xs text-primary font-medium underline"
                >
                  Continue to login
                </Link>
              )}
            </div>
          )}

          <label htmlFor="register-fullname" className="block text-xs font-medium text-muted mb-1">Full name</label>
          <div className="relative mb-1">
            <User size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-muted" />
            <input
              id="register-fullname"
              value={fullName}
              onChange={(e) => setFullName(e.target.value)}
              onBlur={() => markTouched('fullName')}
              required
              placeholder="Enter your full name"
              className="w-full border border-border rounded-lg pl-9 pr-3 py-2.5 text-sm bg-white text-gray-900 focus:outline-none focus:ring-2 focus:ring-primary/30"
            />
          </div>
          <p className="text-[11px] mb-3 h-3.5">
            {touched.fullName && !fullNameValid && (
              <span className="text-danger">Letters, spaces, hyphens, and apostrophes only — no numbers or symbols.</span>
            )}
          </p>

          <label htmlFor="register-email" className="block text-xs font-medium text-muted mb-1">Email</label>
          <div className="relative mb-1">
            <Mail size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-muted" />
            <input
              id="register-email"
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              onBlur={() => markTouched('email')}
              required
              placeholder="you@example.com"
              className="w-full border border-border rounded-lg pl-9 pr-9 py-2.5 text-sm bg-white text-gray-900 focus:outline-none focus:ring-2 focus:ring-primary/30"
            />
            {emailValid && (
              <CheckCircle2 size={16} className="absolute right-3 top-1/2 -translate-y-1/2 text-success" />
            )}
          </div>
          <p className="text-[11px] mb-3 h-3.5">
            {touched.email && !emailValid && <span className="text-danger">Enter a valid email address.</span>}
          </p>

          <label htmlFor="register-phone" className="block text-xs font-medium text-muted mb-1">Mobile number</label>
          <div className="relative mb-1">
            {/* Fixed, non-editable prefix -- the country code is no longer something typed into
                the field at all (see sanitizeLocalPhoneNumber's own comment), so there's nothing
                for a stray keystroke to corrupt here. pointer-events-none so a click on this
                prefix still focuses the actual input right next to it, not a dead zone. */}
            <div className="absolute left-3 top-1/2 -translate-y-1/2 flex items-center gap-1.5 text-sm text-ink pointer-events-none select-none">
              <span aria-hidden="true">🇮🇳</span>
              <span>+91</span>
              <span className="w-px h-4 bg-border" />
            </div>
            <input
              id="register-phone"
              type="tel"
              inputMode="numeric"
              value={phoneNumber}
              onChange={(e) => setPhoneNumber(sanitizeLocalPhoneNumber(e.target.value))}
              onPaste={(e) => {
                e.preventDefault();
                setPhoneNumber(sanitizePastedPhoneNumber(e.clipboardData.getData('text')));
              }}
              onBlur={() => markTouched('phoneNumber')}
              required
              placeholder="XXXXXXXXXX"
              maxLength={10}
              title="10-digit mobile number"
              className="w-full border border-border rounded-lg pl-[4.75rem] pr-3 py-2.5 text-sm bg-white text-gray-900 focus:outline-none focus:ring-2 focus:ring-primary/30"
            />
          </div>
          <p className="text-[11px] mb-3 h-3.5">
            {touched.phoneNumber && !phoneValid && (
              <span className="text-danger">Enter a valid 10-digit mobile number (no leading 0-5).</span>
            )}
          </p>

          <label htmlFor="register-password" className="block text-xs font-medium text-muted mb-1">Password (min 8 characters)</label>
          <PasswordInput
            id="register-password"
            value={password}
            onChange={setPassword}
            onBlur={() => markTouched('password')}
            required
            minLength={8}
            maxLength={72}
            className="w-full border border-border rounded-lg px-3 py-2.5 pr-10 text-sm bg-white text-gray-900 focus:outline-none focus:ring-2 focus:ring-primary/30"
          />
          {password.length > 0 && (
            <div className="mt-2 mb-1">
              <div className="flex gap-1 mb-1">
                {[0, 1, 2, 3].map((i) => (
                  <div key={i} className={`h-1 flex-1 rounded-full ${i < strength.score ? strength.color : 'bg-gray-200'}`} />
                ))}
              </div>
              <p className="text-[11px] text-muted">{strength.label}</p>
            </div>
          )}
          <p className="text-[11px] mb-3 h-3.5">
            {touched.password && !passwordLongEnough && password.length === 0 && (
              <span className="text-danger">Password is required.</span>
            )}
          </p>

          <label htmlFor="register-confirm-password" className="block text-xs font-medium text-muted mb-1">Confirm password</label>
          <PasswordInput
            id="register-confirm-password"
            value={confirmPassword}
            onChange={setConfirmPassword}
            onBlur={() => markTouched('confirmPassword')}
            required
            className="w-full border border-border rounded-lg px-3 py-2.5 pr-10 text-sm bg-white text-gray-900 focus:outline-none focus:ring-2 focus:ring-primary/30"
          />
          {touched.confirmPassword && !passwordsMatch && (
            <p className="text-danger text-xs mt-1">Passwords don't match.</p>
          )}

          <label className="flex items-start gap-2 mt-4 mb-4 cursor-pointer">
            <input
              type="checkbox"
              checked={agreedToTerms}
              onChange={(e) => setAgreedToTerms(e.target.checked)}
              className="mt-0.5 rounded border-border"
            />
            <span className="text-xs text-muted">
              {/* Bug fix: target="_blank" with no rel opened these two same-app tabs with a live
                  `window.opener` handle back to this registration form still in progress -- the
                  classic reverse-tabnabbing shape, and also (pre-Chrome 88/Firefox 79) kept the
                  new tab on the same process/thread as this one. `noopener` severs that handle;
                  `noreferrer` additionally drops the Referer header, which is the right default
                  even for an internal link since neither page needs to know the other opened it. */}
              I agree to Finora's <Link to="/terms" target="_blank" rel="noopener noreferrer" className="text-primary font-medium">Terms of Service</Link> and{' '}
              <Link to="/privacy" target="_blank" rel="noopener noreferrer" className="text-primary font-medium">Privacy Policy</Link>.
            </span>
          </label>

          <div className="flex items-start gap-2.5 bg-primary-light rounded-lg p-3 mb-6">
            <ShieldCheck size={16} className="text-primary flex-shrink-0 mt-0.5" />
            <p className="text-xs text-ink">
              Your financial data is encrypted and securely protected.{' '}
              <Link to="/privacy" target="_blank" rel="noopener noreferrer" className="text-primary font-medium">Read our Privacy Policy</Link>
            </p>
          </div>

          <button
            type="submit"
            disabled={loading || !formValid}
            className="w-full bg-primary hover:bg-primary-dark text-white rounded-lg py-2.5 text-sm font-semibold flex items-center justify-center gap-1.5 disabled:opacity-50"
          >
            {loading ? 'Creating account…' : 'Create account'}
            {!loading && <ArrowRight size={15} />}
          </button>

          <p className="text-sm mt-4 text-center text-muted">
            Already have an account? <Link to="/login" className="text-primary font-medium">Sign in</Link>
          </p>
        </form>
      </div>

      <p className="text-xs text-muted flex items-center gap-2">
        <ShieldCheck size={13} /> Bank-grade encryption. Your data is never sold.
      </p>
    </div>
  );
}
