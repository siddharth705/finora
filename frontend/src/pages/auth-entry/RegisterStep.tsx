import { useMemo, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { ShieldCheck, User, Mail, CheckCircle2, ArrowRight } from 'lucide-react';
import { useAuth } from '../../context/AuthContext';
import { PasswordInput } from '../../components/PasswordInput';
import { GoogleSignInButton } from '../../components/GoogleSignInButton';
import { AppleSignInButton } from '../../components/AppleSignInButton';
import { ReactivateAccountPrompt } from '../../components/ReactivateAccountPrompt';
import { AuthDivider } from './AuthDivider';
import { AUTH_ACCOUNT_DEACTIVATED } from '../../api/errorCodes';

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

function sanitizeLocalPhoneNumber(raw: string): string {
  return raw.replace(/[^0-9]/g, '').slice(0, 10);
}

function sanitizePastedPhoneNumber(raw: string): string {
  const digitsOnly = raw.replace(/[^0-9]/g, '');
  const local = digitsOnly.length > 10 && digitsOnly.startsWith('91') ? digitsOnly.slice(2) : digitsOnly;
  return local.slice(0, 10);
}

const PHONE_PATTERN = /^[6-9][0-9]{9}$/;
const FULL_NAME_PATTERN = /^[\p{L}][\p{L}\s.'-]{0,98}[\p{L}]$/u;
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

interface RegisterStepProps {
  prefill: { email?: string; phoneNumber?: string };
  referralCode: string | undefined;
  onSuccess: (phoneVerified: boolean) => void;
  onAccountExists: (identifier: string) => void;
}

export function RegisterStep({ prefill, referralCode, onSuccess, onAccountExists }: RegisterStepProps) {
  const { register, loginWithGoogle, loginWithApple } = useAuth();
  const [fullName, setFullName] = useState('');
  const [email, setEmail] = useState(prefill.email ?? '');
  const [phoneNumber, setPhoneNumber] = useState(
    prefill.phoneNumber ? sanitizePastedPhoneNumber(prefill.phoneNumber) : '',
  );
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [agreedToTerms, setAgreedToTerms] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [touched, setTouched] = useState<Record<string, boolean>>({});
  const [reactivationToken, setReactivationToken] = useState<string | null>(null);

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
    setTouched({ fullName: true, email: true, phoneNumber: true, password: true, confirmPassword: true });

    if (!fullNameValid) { setError('Enter your full name using letters, spaces, hyphens, or apostrophes only.'); return; }
    if (!emailValid) { setError('Enter a valid email address.'); return; }
    if (!phoneValid) { setError('Enter a valid 10-digit mobile number.'); return; }
    if (!passwordLongEnough) { setError('Password must be at least 8 characters.'); return; }
    if (!passwordsMatch) { setError('Passwords do not match.'); return; }
    if (!agreedToTerms) { setError('Please agree to the Terms & Conditions to continue.'); return; }

    setLoading(true);
    try {
      const { phoneVerified } = await register(email.trim(), password, trimmedName, `+91${phoneNumber}`, referralCode);
      onSuccess(phoneVerified);
    } catch (err: any) {
      setError(err.response?.data?.message ?? 'Registration failed.');
      if (err.response?.status === 409) {
        onAccountExists(email.trim());
      }
    } finally {
      setLoading(false);
    }
  }

  // Same as PasswordStep/IdentifyStep's own OAuth error handling -- AuthService#loginWithOAuthIdentity
  // reports AUTH_ACCOUNT_DEACTIVATED (with a one-time reactivation token) whenever the Google/Apple
  // account's email matches an EXISTING but deactivated account, reachable here regardless of what
  // this form's own fields say since Google/Apple's returned email need not match them at all.
  // Checked before the existing 403 -> onAccountExists fallback, which covers the other 403 case
  // (an existing, active-but-unverified account) that fallback was already written for.
  function handleOAuthError(err: any, fallbackMessage: string) {
    const token = err.response?.data?.errorCode === AUTH_ACCOUNT_DEACTIVATED
      ? err.response?.data?.details?.reactivationToken
      : null;
    if (token) {
      setReactivationToken(token);
      return;
    }
    setError(err.response?.data?.message ?? fallbackMessage);
    if (err.response?.status === 403) onAccountExists(email.trim());
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

  async function handleAppleCredential(idToken: string, fullNameFromApple: string | null) {
    setError(null);
    setLoading(true);
    try {
      onSuccess(await loginWithApple(idToken, fullNameFromApple));
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
      <h2 className="text-2xl font-bold text-ink mb-1">Create your account</h2>
      <p className="text-sm text-muted mb-6">Start your journey towards financial clarity</p>

      {error && <p className="text-danger text-sm mb-4">{error}</p>}

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
          I agree to Fynora's <Link to="/terms" target="_blank" rel="noopener noreferrer" className="text-primary font-medium">Terms of Service</Link> and{' '}
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
        className="w-full bg-primary hover:bg-primary-dark text-on-primary rounded-lg py-2.5 text-sm font-semibold flex items-center justify-center gap-1.5 disabled:opacity-50"
      >
        {loading ? 'Creating account…' : 'Create account'}
        {!loading && <ArrowRight size={15} />}
      </button>

      <AuthDivider />

      <GoogleSignInButton text="signup_with" onCredential={handleGoogleCredential} onError={setError} />
      <div className="mt-3">
        <AppleSignInButton onCredential={handleAppleCredential} onError={setError} />
      </div>
    </form>
  );
}
