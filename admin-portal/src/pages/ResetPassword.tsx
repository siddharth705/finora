import { useEffect, useMemo, useState } from 'react';
import type { FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { ShieldAlert } from 'lucide-react';
import { authApi } from '../api/endpoints';

// Same heuristic as frontend/'s Register.tsx/ResetPassword.tsx -- kept identical so the meter
// means the same thing wherever a password gets set anywhere in Finora, admin or not.
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
 * to change a password outright, including for admin accounts. Now requires an OTP sent to the
 * account's phone as a second factor, same principle VerifyPhone already applies elsewhere, and
 * the exact same backend endpoints the user-facing app's ResetPassword.tsx uses.
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
    requestOtp();
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
    <div className="min-h-screen bg-bg flex items-center justify-center p-4">
      <div className="w-full max-w-sm">
        <div className="flex items-center gap-2.5 justify-center mb-8">
          <div className="w-9 h-9 rounded-lg bg-gradient-to-br from-rose-400 to-primary-dark flex items-center justify-center">
            <ShieldAlert size={18} className="text-white" strokeWidth={2.5} />
          </div>
          <span className="font-extrabold tracking-wide text-xl text-ink">FINORA ADMIN</span>
        </div>

        <div className="bg-card border border-border rounded-xl2 shadow-soft p-6">
          {done ? (
            <>
              <h1 className="text-lg font-bold text-ink mb-2">Password updated</h1>
              <p className="text-sm text-muted">Redirecting you to sign in…</p>
            </>
          ) : (
            <form onSubmit={handleSubmit} noValidate>
              <h1 className="text-lg font-bold text-ink mb-2">Set a new password</h1>
              {!token && (
                <p className="text-xs text-danger mb-4">
                  No reset token found in the link — this page normally opens from the email link.
                </p>
              )}
              {error && <p className="text-sm text-danger bg-danger-bg rounded-lg px-3.5 py-2.5 mb-4">{error}</p>}

              {token && (
                <div className="mb-4">
                  <label className="block text-sm font-medium text-ink mb-1">Verification code</label>
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
                    className="w-full bg-bg border border-border rounded-lg px-3.5 py-2.5 text-center text-lg tracking-[0.4em] font-mono focus:outline-none focus:ring-2 focus:ring-primary/30 mb-1"
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

              <label className="block text-sm font-medium text-ink mb-1.5">New password</label>
              <input
                type="password"
                required
                minLength={8}
                maxLength={72}
                autoComplete="new-password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                onBlur={() => markTouched('password')}
                className="w-full bg-bg border border-border rounded-lg px-3.5 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-primary/30"
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

              <label className="block text-sm font-medium text-ink mb-1.5">Confirm password</label>
              <input
                type="password"
                required
                autoComplete="new-password"
                value={confirm}
                onChange={(e) => setConfirm(e.target.value)}
                onBlur={() => markTouched('confirm')}
                className="w-full bg-bg border border-border rounded-lg px-3.5 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-primary/30 mb-1"
              />
              <p className="text-[11px] mb-4 h-3.5">
                {touched.confirm && !passwordsMatch && <span className="text-danger">Passwords don't match.</span>}
              </p>

              <button
                type="submit"
                disabled={loading || !formValid}
                className="w-full bg-primary hover:bg-primary-dark text-white font-semibold rounded-lg py-2.5 text-sm disabled:opacity-50"
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
    </div>
  );
}
