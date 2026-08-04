import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { ShieldAlert, ShieldCheck } from 'lucide-react';
import { phoneApi, userApi } from '../api/endpoints';
import { useAdminAuth } from '../context/AdminAuthContext';
import { sendPhoneVerificationCode, confirmPhoneVerificationCode, resetPhoneVerification } from '../lib/phoneAuth';
import { maskPhone } from '../lib/maskPhone';
import type { ConfirmationResult } from 'firebase/auth';

const RECAPTCHA_CONTAINER_ID = 'verify-phone-recaptcha';

// Firebase's own error codes for the two cases worth a specific message rather than its generic
// one -- same as frontend/'s VerifyPhone.tsx.
function friendlyFirebaseError(err: any): string {
  switch (err?.code) {
    case 'auth/invalid-verification-code':
      return "That code doesn't match — check and try again.";
    case 'auth/code-expired':
      return 'This code has expired. Request a new one.';
    default:
      return 'Could not verify — try again.';
  }
}

/**
 * Reuses the exact same /phone/verify endpoint and Firebase Phone Authentication flow the
 * user-facing app (frontend/) already uses -- there is only one verification implementation,
 * this is just the admin portal's own screen for it. See
 * docs/adr/0001-administrator-verification-strategy.md: the fix here is presenting this existing
 * capability, not building a parallel one or exempting administrators from it.
 *
 * Reached from Login.tsx when login() resolves to phoneVerified === false, and from the app-level
 * redirect for anyone who lands elsewhere mid-verification. The token from login() is still
 * valid the whole time this page is in play -- AdminAuthContext no longer clears the session just
 * because verification is still pending.
 */
export default function VerifyPhone() {
  const navigate = useNavigate();
  const { completePhoneVerification, logout } = useAdminAuth();
  const [otp, setOtp] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [phoneNumber, setPhoneNumber] = useState<string | null>(null);
  const [confirmation, setConfirmation] = useState<ConfirmationResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [sending, setSending] = useState(false);
  const startedRef = useRef(false);

  async function startVerification() {
    setSending(true);
    setError(null);
    setOtp('');
    try {
      // The account's real phone number is fetched fresh here (this page is only ever reached
      // authenticated) and handed straight to Firebase, which sends the code itself -- this
      // backend never does. Covers both an admin-created account (no OTP auto-sent before this
      // page existed) and a returning admin whose phone still isn't verified.
      const settings = await userApi.get();
      setPhoneNumber(settings.phoneNumber);
      const result = await sendPhoneVerificationCode(settings.phoneNumber, RECAPTCHA_CONTAINER_ID);
      setConfirmation(result);
    } catch {
      setError('Could not send a verification code right now.');
    } finally {
      setSending(false);
    }
  }

  useEffect(() => {
    if (startedRef.current) return;
    startedRef.current = true;
    void startVerification();
    return () => resetPhoneVerification();
  }, []);

  async function handleVerify(e: FormEvent) {
    e.preventDefault();
    if (!confirmation) return;
    setError(null);
    setLoading(true);
    try {
      const idToken = await confirmPhoneVerificationCode(confirmation, otp);
      await phoneApi.verify(idToken);
      await completePhoneVerification();
      void navigate('/');
    } catch (err: any) {
      setError(err?.message ?? err?.response?.data?.message ?? friendlyFirebaseError(err));
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
          <div className="flex items-center gap-2 mb-2">
            <ShieldCheck size={20} className="text-primary" />
            <h1 className="text-lg font-bold text-ink">Verify your phone</h1>
          </div>
          <p className="text-sm text-muted mb-6">
            Your credentials checked out, but this account still needs phone verification before
            it can open the admin portal. Enter the 6-digit code sent to{' '}
            {phoneNumber ? maskPhone(phoneNumber) : 'your mobile number'}.
          </p>

          {error && <p className="text-sm text-danger bg-danger-bg rounded-lg px-3.5 py-2.5 mb-4">{error}</p>}

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
                disabled={!confirmation}
                className="w-full bg-bg border border-border rounded-lg px-3.5 py-2.5 text-center text-lg tracking-[0.5em] font-mono focus:outline-none focus:ring-2 focus:ring-primary/30 disabled:opacity-50"
              />
            </div>

            <button
              type="submit"
              disabled={loading || !confirmation || otp.length !== 6}
              className="w-full bg-primary hover:bg-primary-dark text-white font-semibold rounded-lg py-2.5 text-sm disabled:opacity-50"
            >
              {loading ? 'Verifying…' : 'Verify'}
            </button>
          </form>

          <button
            onClick={startVerification}
            disabled={sending}
            className="w-full mt-3 text-xs text-primary font-medium text-center"
          >
            {sending ? 'Sending…' : "Didn't get a code? Resend"}
          </button>
        </div>

        <button onClick={logout} className="w-full text-center text-xs text-muted mt-6">
          Not you? Sign out
        </button>

        {/* Anchor for Firebase's invisible reCAPTCHA -- never visibly rendered, but must exist in
            the DOM before sendPhoneVerificationCode() runs. */}
        <div id={RECAPTCHA_CONTAINER_ID} />
      </div>
    </div>
  );
}
