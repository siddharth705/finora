import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { Sparkles, ShieldCheck } from 'lucide-react';
import { phoneApi, userApi } from '../api/endpoints';
import { useAuth } from '../context/AuthContext';
import { sendPhoneVerificationCode, confirmPhoneVerificationCode, resetPhoneVerification } from '../lib/phoneAuth';
import { maskPhone } from '../lib/maskPhone';
import type { ConfirmationResult } from 'firebase/auth';

const RECAPTCHA_CONTAINER_ID = 'verify-phone-recaptcha';

// Firebase's own error codes for the two cases worth a specific message rather than its generic
// one -- everything else falls back to a plain "something went wrong" rather than surfacing raw
// Firebase error text to the user.
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

export default function VerifyPhone() {
  const navigate = useNavigate();
  const { setPhoneVerified } = useAuth();
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
      // The account's real phone number is never carried through router state -- fetched fresh
      // here (this page is only ever reached authenticated, right after register() or login())
      // and handed straight to Firebase, which sends the code itself; this backend never does.
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
      setPhoneVerified(true);
      navigate('/app');
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
          <span className="w-7 h-7 rounded-lg bg-gradient-to-br from-indigo-400 to-primary-dark flex items-center justify-center">
            <Sparkles size={14} className="text-white" strokeWidth={2.5} />
          </span>
          <span className="font-extrabold tracking-wide text-ink">FINORA</span>
        </div>

        <div className="flex items-center gap-2 mb-2">
          <ShieldCheck size={20} className="text-primary" />
          <h1 className="text-2xl font-bold text-ink">Verify your phone</h1>
        </div>
        <p className="text-sm text-muted mb-6">
          Enter the 6-digit code we sent to {phoneNumber ? maskPhone(phoneNumber) : 'your mobile number'}.
        </p>

        {error && <p className="text-danger text-sm mb-4">{error}</p>}

        <form onSubmit={handleVerify}>
          <label htmlFor="verify-phone-otp" className="block text-xs font-medium text-muted mb-1">Verification code</label>
          <input
            id="verify-phone-otp"
            value={otp}
            onChange={(e) => setOtp(e.target.value.replace(/\D/g, '').slice(0, 6))}
            required
            inputMode="numeric"
            placeholder="123456"
            disabled={!confirmation}
            className="bg-white text-gray-900 w-full border border-border rounded-lg px-3 py-2.5 mb-4 text-center text-lg tracking-[0.5em] font-mono focus:outline-none focus:ring-2 focus:ring-primary/30 disabled:opacity-50"
          />

          <button
            type="submit"
            disabled={loading || !confirmation || otp.length !== 6}
            className="w-full bg-primary hover:bg-primary-dark text-white rounded-lg py-2.5 text-sm font-semibold disabled:opacity-50"
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

        {/* Anchor for Firebase's invisible reCAPTCHA -- never visibly rendered, but must exist in
            the DOM before sendPhoneVerificationCode() runs. */}
        <div id={RECAPTCHA_CONTAINER_ID} />
      </div>
    </div>
  );
}
