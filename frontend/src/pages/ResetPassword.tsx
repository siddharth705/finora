import { useMemo, useState } from 'react';
import type { FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { Sparkles } from 'lucide-react';
import { authApi } from '../api/endpoints';
import { PasswordInput } from '../components/PasswordInput';
import {
  sendPhoneVerificationCode,
  confirmPhoneVerificationCode,
  resetPhoneVerification,
  friendlySendError,
} from '../lib/phoneAuth';
import { reportHandledError } from '../lib/monitoring';
import type { ConfirmationResult } from 'firebase/auth';

const RECAPTCHA_CONTAINER_ID = 'reset-password-recaptcha';

// Same heuristic as Register.tsx's passwordStrength -- kept identical so the meter means the
// same thing wherever a user sets a password in this app.
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

function friendlyFirebaseError(err: any): string {
  switch (err?.code) {
    case 'auth/invalid-verification-code':
      return "That code doesn't match — check and try again.";
    case 'auth/code-expired':
      return 'This code has expired. Request a new one.';
    default:
      return 'Could not verify. The code may be wrong, or the link may have expired.';
  }
}

// Mirrors Register.tsx's identical helper -- kept as its own local copy rather than a shared
// module, same as Register.tsx's own (there's no third caller yet to justify extracting one).
const PHONE_PATTERN = /^[6-9][0-9]{9}$/;
function sanitizeLocalPhoneNumber(raw: string): string {
  return raw.replace(/[^0-9]/g, '').slice(0, 10);
}

/**
 * BH-015 fix. A reset link alone (proof of email access) is not sufficient to change a password
 * -- a phone OTP (proof of phone access) via Firebase Phone Authentication is also required, same
 * principle VerifyPhone already applies elsewhere. Previously the account's real phone number was
 * fetched from the backend and handed straight to Firebase -- meaning anyone holding a valid reset
 * link learned the account's real phone number even without ever completing the reset. Now
 * inverted: the USER types their own number; authApi.verifyResetPasswordPhone confirms it matches
 * the account server-side (never returning the real number) BEFORE this page is allowed to hand
 * that same, user-supplied number to Firebase. Firebase then sends and confirms the code entirely
 * client-side, same as before; the backend only ever sees the resulting ID token.
 */
export default function ResetPassword() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const token = searchParams.get('token');

  const [phoneLocal, setPhoneLocal] = useState('');
  const [phoneConfirmed, setPhoneConfirmed] = useState(false);
  const [verifyingPhone, setVerifyingPhone] = useState(false);
  const [phoneError, setPhoneError] = useState<string | null>(null);
  const phoneValid = PHONE_PATTERN.test(phoneLocal);

  const [otp, setOtp] = useState('');
  const [confirmation, setConfirmation] = useState<ConfirmationResult | null>(null);
  const [otpError, setOtpError] = useState<string | null>(null);
  const [sendingOtp, setSendingOtp] = useState(false);
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [touched, setTouched] = useState<Record<string, boolean>>({});
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [done, setDone] = useState(false);

  async function sendOtp(fullPhoneNumber: string) {
    setSendingOtp(true);
    setOtpError(null);
    setOtp('');
    setConfirmation(null);
    try {
      const result = await sendPhoneVerificationCode(fullPhoneNumber, RECAPTCHA_CONTAINER_ID);
      setConfirmation(result);
    } catch (err: any) {
      // Logged, not just displayed -- same reasoning as VerifyPhone.tsx's own fix.
      console.error('ResetPassword: sendOtp failed', err);
      reportHandledError(err, 'reset-password-send-otp');
      // Same fix ChangePasswordModal.tsx already carries -- see resetPhoneVerification's own doc
      // comment: a consumed/expired invisible-reCAPTCHA widget throws on reuse, so a retry needs
      // a fresh verifier regardless of what just failed.
      resetPhoneVerification();
      setOtpError(friendlySendError(err));
    } finally {
      setSendingOtp(false);
    }
  }

  async function submitPhoneNumber(e: FormEvent) {
    e.preventDefault();
    setTouched((t) => ({ ...t, phone: true }));
    if (!token || !phoneValid) return;
    setPhoneError(null);
    setVerifyingPhone(true);
    const fullPhoneNumber = `+91${phoneLocal}`;
    try {
      await authApi.verifyResetPasswordPhone(token, fullPhoneNumber);
      setPhoneConfirmed(true);
      await sendOtp(fullPhoneNumber);
    } catch (err: any) {
      setPhoneError(err.response?.data?.message ?? 'Could not verify that phone number. Please try again.');
    } finally {
      setVerifyingPhone(false);
    }
  }

  const passwordLongEnough = password.length >= 8;
  const passwordsMatch = confirm.length > 0 && confirm === password;
  const otpValid = /^\d{6}$/.test(otp);
  const strength = useMemo(() => passwordStrength(password), [password]);
  const formValid = Boolean(token) && Boolean(confirmation) && otpValid && passwordLongEnough && passwordsMatch;

  function markTouched(field: string) {
    setTouched((t) => ({ ...t, [field]: true }));
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setTouched((t) => ({ ...t, password: true, confirm: true, otp: true }));
    if (!token) { setError('No reset token found in the link.'); return; }
    if (!confirmation) { setError('Enter the 6-digit verification code sent to your phone.'); return; }
    if (!otpValid) { setError('Enter the 6-digit verification code sent to your phone.'); return; }
    if (!passwordLongEnough) { setError('Password must be at least 8 characters.'); return; }
    if (!passwordsMatch) { setError('Passwords do not match.'); return; }
    setError(null);
    setLoading(true);
    try {
      const firebaseIdToken = await confirmPhoneVerificationCode(confirmation, otp);
      await authApi.resetPassword(token, firebaseIdToken, password);
      setDone(true);
      setTimeout(() => {
        // D-26 unified entry: skips straight to /auth's password step rather than the identify
        // step, since this user just proved phone ownership for an account that definitely
        // exists. No `identifier` to prefill with, though -- ResetPasswordResponse only ever
        // carries a message, never the account's email.
        void navigate('/auth', {
          state: {
            banner: 'Password reset successfully. Please sign in using your new password.',
            skipToPassword: true,
          },
        });
      }, 2000);
    } catch (err: any) {
      setError(err.response?.data?.message ?? friendlyFirebaseError(err));
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-bg">
      <div className="bg-card rounded-xl2 p-8 w-full max-w-sm shadow-soft border border-border">
        <div className="flex items-center gap-2 mb-6">
          <span className="w-7 h-7 rounded-lg bg-gradient-to-br from-primary to-primary-dark flex items-center justify-center">
            <Sparkles size={14} className="text-on-primary" strokeWidth={2.5} />
          </span>
          <span className="font-extrabold tracking-wide text-ink">FYNORA</span>
        </div>

        {/* Anchor for Firebase's invisible reCAPTCHA -- rendered once, outside the phone/otp
            step conditional below, and never unmounted while this page is up. sendOtp() (called
            from BOTH the phone step's submit and the otp step's "Resend code") constructs
            Firebase's RecaptchaVerifier synchronously against this exact DOM node the first time
            it's called; a copy of this anchor duplicated into each step's own branch would get
            swapped out from under an in-flight verifier the instant setPhoneConfirmed(true)
            triggers the step transition, since React doesn't guarantee the old node survives
            until Firebase finishes attaching to it. One persistent anchor sidesteps that race
            entirely instead of depending on how the two effects happen to interleave. */}
        <div id={RECAPTCHA_CONTAINER_ID} />

        {done ? (
          <>
            <h1 className="text-2xl font-bold mb-3 text-ink">Password updated</h1>
            <p className="text-sm text-muted">Redirecting you to sign in…</p>
          </>
        ) : !token ? (
          <p className="text-xs text-danger">
            No reset token found in the link — this page normally opens from the email link.
          </p>
        ) : !phoneConfirmed ? (
          <form onSubmit={submitPhoneNumber} noValidate>
            <h1 className="text-2xl font-bold mb-2 text-ink">Confirm your phone number</h1>
            <p className="text-xs text-muted mb-4">
              Enter the mobile number on this account. We'll use it to send a verification code.
            </p>
            {phoneError && <p className="text-danger text-sm mb-4">{phoneError}</p>}

            <label htmlFor="reset-phone" className="block text-xs font-medium text-muted mb-1">Mobile number</label>
            <div className="relative mb-1">
              <div className="absolute left-3 top-1/2 -translate-y-1/2 flex items-center gap-1.5 text-sm text-ink pointer-events-none select-none">
                <span>+91</span>
                <span className="w-px h-4 bg-border" />
              </div>
              <input
                id="reset-phone"
                type="tel"
                inputMode="numeric"
                value={phoneLocal}
                onChange={(e) => { setPhoneLocal(sanitizeLocalPhoneNumber(e.target.value)); setPhoneError(null); }}
                onBlur={() => markTouched('phone')}
                placeholder="XXXXXXXXXX"
                maxLength={10}
                className="w-full border border-border rounded-lg pl-[3.75rem] pr-3 py-2.5 text-sm bg-white text-gray-900 focus:outline-none focus:ring-2 focus:ring-primary/30"
              />
            </div>
            <p className="text-[11px] mb-4 h-3.5">
              {touched.phone && !phoneValid && <span className="text-danger">Enter a valid 10-digit mobile number.</span>}
            </p>

            <button
              type="submit"
              disabled={verifyingPhone || !phoneValid}
              className="w-full bg-primary hover:bg-primary-dark text-on-primary rounded-lg py-2.5 text-sm font-semibold disabled:opacity-50"
            >
              {verifyingPhone ? 'Confirming…' : 'Continue'}
            </button>

            <p className="text-sm mt-4 text-center">
              <Link to="/auth" className="text-primary font-medium">Back to sign in</Link>
            </p>
          </form>
        ) : (
          <form onSubmit={handleSubmit} noValidate>
            <h1 className="text-2xl font-bold mb-2 text-ink">Set a new password</h1>
            {error && <p className="text-danger text-sm mb-4">{error}</p>}

            <div className="mb-4">
              <label htmlFor="reset-otp" className="block text-xs font-medium text-muted mb-1">Verification code</label>
              <p className="text-xs text-muted mb-2">
                {confirmation
                  ? `Enter the 6-digit code sent to +91${phoneLocal}.`
                  : 'Sending a verification code…'}
              </p>
              {otpError && <p className="text-danger text-xs mb-2">{otpError}</p>}
              <input
                id="reset-otp"
                value={otp}
                onChange={(e) => setOtp(e.target.value.replace(/\D/g, '').slice(0, 6))}
                onBlur={() => markTouched('otp')}
                inputMode="numeric"
                placeholder="123456"
                disabled={!confirmation}
                className="bg-white text-gray-900 w-full border border-border rounded-lg px-3 py-2.5 text-center text-lg tracking-[0.4em] font-mono focus:outline-none focus:ring-2 focus:ring-primary/30 mb-1 disabled:opacity-50"
              />
              <div className="flex items-center justify-between">
                <p className="text-[11px] h-3.5">
                  {touched.otp && !otpValid && <span className="text-danger">Enter the 6-digit code.</span>}
                </p>
                <button
                  type="button"
                  onClick={() => void sendOtp(`+91${phoneLocal}`)}
                  disabled={sendingOtp}
                  className="text-[11px] text-primary font-medium"
                >
                  {sendingOtp ? 'Sending…' : 'Resend code'}
                </button>
              </div>
            </div>

            <label htmlFor="reset-new-password" className="block text-xs font-medium text-muted mb-1">New password</label>
            <PasswordInput
              id="reset-new-password"
              value={password}
              onChange={setPassword}
              onBlur={() => markTouched('password')}
              required
              minLength={8}
              maxLength={72}
              autoComplete="new-password"
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
              {touched.password && !passwordLongEnough && password.length > 0 && (
                <span className="text-danger">Password must be at least 8 characters.</span>
              )}
            </p>

            <label htmlFor="reset-confirm-password" className="block text-xs font-medium text-muted mb-1">Confirm password</label>
            <PasswordInput
              id="reset-confirm-password"
              value={confirm}
              onChange={setConfirm}
              onBlur={() => markTouched('confirm')}
              required
              autoComplete="new-password"
              className="w-full border border-border rounded-lg px-3 py-2.5 pr-10 mb-1 text-sm bg-white text-gray-900 focus:outline-none focus:ring-2 focus:ring-primary/30"
            />
            <p className="text-[11px] mb-4 h-3.5">
              {touched.confirm && !passwordsMatch && <span className="text-danger">Passwords don't match.</span>}
            </p>

            <button
              type="submit"
              disabled={loading || !formValid}
              className="w-full bg-primary hover:bg-primary-dark text-on-primary rounded-lg py-2.5 text-sm font-semibold disabled:opacity-50"
            >
              {loading ? 'Updating…' : 'Update password'}
            </button>

            <p className="text-sm mt-4 text-center">
              <Link to="/auth" className="text-primary font-medium">Back to sign in</Link>
            </p>
          </form>
        )}
      </div>
    </div>
  );
}
