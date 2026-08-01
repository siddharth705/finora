import { useEffect, useMemo, useState } from 'react';
import type { FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { Sparkles } from 'lucide-react';
import { authApi } from '../api/endpoints';
import { PasswordInput } from '../components/PasswordInput';

// Same heuristic as Register.tsx's passwordStrength — kept identical so the meter means the
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

/**
 * Bug fix / security hardening: a reset link alone (proof of email access) used to be sufficient
 * to change a password outright. That's now a two-factor flow -- an OTP sent to the account's
 * phone number is also required, same principle VerifyPhone already applies elsewhere. The OTP
 * is requested automatically on mount (as soon as we know the link's token is present), same
 * "don't make the user take an extra action to get the code" convention Register.tsx/VerifyPhone
 * already establish.
 */
export default function ResetPassword() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const token = searchParams.get('token');
  const [otp, setOtp] = useState('');
  const [otpRequested, setOtpRequested] = useState(false);
  const [otpDevCode, setOtpDevCode] = useState<string | null>(null);
  const [otpError, setOtpError] = useState<string | null>(null);
  const [resendingOtp, setResendingOtp] = useState(false);
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [touched, setTouched] = useState<Record<string, boolean>>({});
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [done, setDone] = useState(false);

  useEffect(() => {
    if (!token) return;
    void requestOtp();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token]);

  async function requestOtp() {
    if (!token) return;
    setResendingOtp(true);
    setOtpError(null);
    // A resend invalidates whatever code was issued before (OtpService.issueOtp() replaces the
    // still-active one) -- clearing here so a stale, now-wrong code can't sit in the field
    // looking valid and get submitted, which would just fail with a confusing "incorrect code"
    // even though the user typed something that WAS right a moment ago.
    setOtp('');
    try {
      const res = await authApi.requestPasswordResetOtp(token);
      setOtpDevCode(res.devOtp);
      setOtpRequested(true);
    } catch (err: any) {
      setOtpError(err.response?.data?.message ?? 'Could not send a verification code. The link may be invalid or expired.');
    } finally {
      setResendingOtp(false);
    }
  }

  const passwordLongEnough = password.length >= 8;
  const passwordsMatch = confirm.length > 0 && confirm === password;
  const otpValid = /^\d{6}$/.test(otp);
  const strength = useMemo(() => passwordStrength(password), [password]);
  const formValid = Boolean(token) && otpValid && passwordLongEnough && passwordsMatch;

  function markTouched(field: string) {
    setTouched((t) => ({ ...t, [field]: true }));
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setTouched({ password: true, confirm: true, otp: true });
    if (!token) { setError('No reset token found in the link.'); return; }
    if (!otpValid) { setError('Enter the 6-digit verification code sent to your phone.'); return; }
    if (!passwordLongEnough) { setError('Password must be at least 8 characters.'); return; }
    if (!passwordsMatch) { setError('Passwords do not match.'); return; }
    setError(null);
    setLoading(true);
    try {
      await authApi.resetPassword(token, otp, password);
      setDone(true);
      setTimeout(() => navigate('/login'), 2000);
    } catch (err: any) {
      setError(err.response?.data?.message ?? 'Could not reset password. The code may be wrong or the link may have expired.');
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-bg">
      <div className="bg-card rounded-xl2 p-8 w-full max-w-sm shadow-soft border border-border">
        <div className="flex items-center gap-2 mb-6">
          <span className="w-7 h-7 rounded-lg bg-gradient-to-br from-indigo-400 to-primary-dark flex items-center justify-center">
            <Sparkles size={14} className="text-white" strokeWidth={2.5} />
          </span>
          <span className="font-extrabold tracking-wide text-ink">FINORA</span>
        </div>

        {done ? (
          <>
            <h1 className="text-2xl font-bold mb-3 text-ink">Password updated</h1>
            <p className="text-sm text-muted">Redirecting you to sign in…</p>
          </>
        ) : (
          <form onSubmit={handleSubmit} noValidate>
            <h1 className="text-2xl font-bold mb-2 text-ink">Set a new password</h1>
            {!token && (
              <p className="text-xs text-danger mb-4">
                No reset token found in the link — this page normally opens from the email link.
              </p>
            )}
            {error && <p className="text-danger text-sm mb-4">{error}</p>}

            {token && (
              <div className="mb-4">
                <label className="block text-xs font-medium text-muted mb-1">Verification code</label>
                <p className="text-xs text-muted mb-2">
                  {otpRequested ? 'Enter the 6-digit code sent to the phone number on file.' : 'Sending a verification code…'}
                </p>
                {otpError && <p className="text-danger text-xs mb-2">{otpError}</p>}
                {otpDevCode && (
                  <div className="bg-primary-light border border-primary/20 rounded-lg p-2.5 mb-2 text-xs">
                    <p className="mb-0.5 font-medium uppercase text-[10px] text-primary">
                      No SMS provider configured yet — dev code:
                    </p>
                    <p className="font-mono text-sm tracking-widest">{otpDevCode}</p>
                  </div>
                )}
                <input
                  value={otp}
                  onChange={(e) => setOtp(e.target.value.replace(/\D/g, '').slice(0, 6))}
                  onBlur={() => markTouched('otp')}
                  inputMode="numeric"
                  placeholder="123456"
                  className="bg-white text-gray-900 w-full border border-border rounded-lg px-3 py-2.5 text-center text-lg tracking-[0.4em] font-mono focus:outline-none focus:ring-2 focus:ring-primary/30 mb-1"
                />
                <div className="flex items-center justify-between">
                  <p className="text-[11px] h-3.5">
                    {touched.otp && !otpValid && <span className="text-danger">Enter the 6-digit code.</span>}
                  </p>
                  <button
                    type="button"
                    onClick={requestOtp}
                    disabled={resendingOtp}
                    className="text-[11px] text-primary font-medium"
                  >
                    {resendingOtp ? 'Sending…' : 'Resend code'}
                  </button>
                </div>
              </div>
            )}

            <label className="block text-xs font-medium text-muted mb-1">New password</label>
            <PasswordInput
              value={password}
              onChange={setPassword}
              onBlur={() => markTouched('password')}
              required
              minLength={8}
              maxLength={72}
              autoComplete="new-password"
              className="w-full border border-border rounded-lg px-3 py-2.5 pr-10 text-sm focus:outline-none focus:ring-2 focus:ring-primary/30"
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

            <label className="block text-xs font-medium text-muted mb-1">Confirm password</label>
            <PasswordInput
              value={confirm}
              onChange={setConfirm}
              onBlur={() => markTouched('confirm')}
              required
              autoComplete="new-password"
              className="w-full border border-border rounded-lg px-3 py-2.5 pr-10 mb-1 text-sm focus:outline-none focus:ring-2 focus:ring-primary/30"
            />
            <p className="text-[11px] mb-4 h-3.5">
              {touched.confirm && !passwordsMatch && <span className="text-danger">Passwords don't match.</span>}
            </p>

            <button
              type="submit"
              disabled={loading || !formValid}
              className="w-full bg-primary hover:bg-primary-dark text-white rounded-lg py-2.5 text-sm font-semibold disabled:opacity-50"
            >
              {loading ? 'Updating…' : 'Update password'}
            </button>

            <p className="text-sm mt-4 text-center">
              <Link to="/login" className="text-primary font-medium">Back to sign in</Link>
            </p>
          </form>
        )}
      </div>
    </div>
  );
}
