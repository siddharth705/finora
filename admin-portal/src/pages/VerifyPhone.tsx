import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { ShieldAlert, ShieldCheck } from 'lucide-react';
import { phoneApi } from '../api/endpoints';
import { useAdminAuth } from '../context/AdminAuthContext';

/**
 * Reuses the exact same /phone/send-otp and /phone/verify-otp endpoints the user-facing app
 * (frontend/) already calls -- there is only one verification implementation, this is just the
 * admin portal's own screen for it. See docs/adr/0001-administrator-verification-strategy.md:
 * the fix here is presenting this existing capability, not building a parallel one or exempting
 * administrators from it.
 *
 * Reached from Login.tsx when login() resolves to phoneVerified === false, and from the app-level
 * redirect below for anyone who lands elsewhere mid-verification (e.g. a page reload while on
 * this screen). The token from login() is still valid the whole time this page is in play --
 * AdminAuthContext no longer clears the session just because verification is still pending.
 */
export default function VerifyPhone() {
  const navigate = useNavigate();
  const { completePhoneVerification, logout } = useAdminAuth();
  const [otp, setOtp] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);
  const [devOtp, setDevOtp] = useState<string | null>(null);
  // Which number the code was sent to -- lets this screen show something more useful than "your
  // mobile number," and lets a wrong/missing country code on the account be visible on screen
  // instead of silently failing to deliver via the SMS provider.
  const [maskedPhone, setMaskedPhone] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [resending, setResending] = useState(false);

  useEffect(() => {
    // Unlike self-registration (which issues the first OTP as part of the register call itself
    // -- see AuthService.register / frontend's VerifyPhone seeding devOtp from router state),
    // an admin-created account (SetupService.completeSetup -> AuthService.adminCreateUser)
    // deliberately does NOT get one sent automatically. Without this, the caller would land on
    // a code-entry form with no code ever having been sent, and no visible reason why.
    void handleResend();
    // handleResend only closes over stable setState setters and the phoneApi import, so an
    // empty dependency array is correct as-is -- no suppression needed.
  }, []);

  async function handleVerify(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      const res = await phoneApi.verifyOtp(otp);
      if (res.verified) {
        await completePhoneVerification();
        navigate('/');
      } else {
        setError(res.message);
      }
    } catch (err: any) {
      setError(err?.message ?? err?.response?.data?.message ?? 'Could not verify — try again.');
    } finally {
      setLoading(false);
    }
  }

  async function handleResend() {
    setResending(true);
    setError(null);
    setInfo(null);
    setDevOtp(null);
    try {
      const res = await phoneApi.sendOtp();
      setInfo(res.message);
      setDevOtp(res.devOtp);
      setMaskedPhone(res.maskedPhone);
    } catch (err: any) {
      setError(err?.response?.data?.message ?? 'Could not send a new code right now.');
    } finally {
      setResending(false);
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
          <div className="flex items-center gap-2 mb-2">
            <ShieldCheck size={20} className="text-primary" />
            <h1 className="text-lg font-bold text-ink">Verify your phone</h1>
          </div>
          <p className="text-sm text-muted mb-6">
            Your credentials checked out, but this account still needs phone verification before
            it can open the admin portal. Enter the 6-digit code sent to {maskedPhone ?? 'your mobile number'}.
          </p>

          {error && <p className="text-sm text-danger bg-danger-bg rounded-lg px-3.5 py-2.5 mb-4">{error}</p>}
          {info && <p className="text-sm text-success mb-2">{info}</p>}
          {devOtp && (
            <div className="bg-primary-light border border-primary/20 rounded-lg p-3 mb-4 text-xs">
              <p className="font-medium uppercase text-[10px] text-primary mb-1">
                No SMS provider configured yet — dev code:
              </p>
              <p className="font-mono text-base tracking-widest">{devOtp}</p>
            </div>
          )}

          <form onSubmit={handleVerify} className="space-y-4">
            <div>
              <label className="block text-sm font-medium text-ink mb-1.5">Verification code</label>
              <input
                value={otp}
                onChange={(e) => setOtp(e.target.value.replace(/\D/g, '').slice(0, 6))}
                required
                autoFocus
                inputMode="numeric"
                placeholder="123456"
                className="w-full bg-bg border border-border rounded-lg px-3.5 py-2.5 text-center text-lg tracking-[0.5em] font-mono focus:outline-none focus:ring-2 focus:ring-primary/30"
              />
            </div>

            <button
              type="submit"
              disabled={loading || otp.length !== 6}
              className="w-full bg-primary hover:bg-primary-dark text-white font-semibold rounded-lg py-2.5 text-sm disabled:opacity-50"
            >
              {loading ? 'Verifying…' : 'Verify'}
            </button>
          </form>

          <button
            onClick={handleResend}
            disabled={resending}
            className="w-full mt-3 text-xs text-primary font-medium text-center"
          >
            {resending ? 'Sending…' : "Didn't get a code? Resend"}
          </button>
        </div>

        <button onClick={logout} className="w-full text-center text-xs text-muted mt-6">
          Not you? Sign out
        </button>
      </div>
    </div>
  );
}
