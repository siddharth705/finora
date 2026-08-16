import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { Sparkles, ShieldCheck, Loader2 } from 'lucide-react';
import { phoneApi, userApi } from '../api/endpoints';
import { useAuth } from '../context/AuthContext';
import {
  sendPhoneVerificationCode,
  confirmPhoneVerificationCode,
  resetPhoneVerification,
  friendlySendError,
} from '../lib/phoneAuth';
import { reportHandledError } from '../lib/monitoring';
import { maskPhone } from '../lib/maskPhone';
import type { ConfirmationResult } from 'firebase/auth';

const RECAPTCHA_CONTAINER_ID = 'verify-phone-recaptcha';

// Purely a client-side courtesy against accidental double-clicks -- Firebase's own
// auth/too-many-requests is the real rate limit, this just avoids racking those up needlessly.
const RESEND_COOLDOWN_SECONDS = 30;

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
  const { setPhoneVerified, logout } = useAuth();
  const [otp, setOtp] = useState('');
  // Kept as two separate states rather than one -- a failed *send* (Firebase down, bad config,
  // quota) means the user has no code at all and needs a real way out (hence the Logout escape
  // hatch shown only alongside this one); a failed *verify* (mistyped/expired code) means a code
  // did arrive and the fix is just retyping it, not logging out.
  const [sendError, setSendError] = useState<string | null>(null);
  const [verifyError, setVerifyError] = useState<string | null>(null);
  const [phoneNumber, setPhoneNumber] = useState<string | null>(null);
  const [confirmation, setConfirmation] = useState<ConfirmationResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [sending, setSending] = useState(false);
  const [resendCooldown, setResendCooldown] = useState(0);
  const startedRef = useRef(false);

  // Ticks the resend cooldown down to 0 once a second. Only runs while there's actually a
  // cooldown in progress, so this is a no-op for almost the entire life of the page.
  useEffect(() => {
    if (resendCooldown <= 0) return;
    const id = setInterval(() => setResendCooldown((s) => Math.max(0, s - 1)), 1000);
    return () => clearInterval(id);
  }, [resendCooldown]);

  // isUserInitiated distinguishes an explicit "Resend"/"Try again" click from the automatic send
  // on mount -- only a click starts the cooldown below. The auto-send isn't something the user
  // did, so there's nothing to protect against hammering yet; the cooldown exists to stop rapid
  // repeated clicks of the resend control itself, not to make a user wait out an arbitrary timer
  // before their very first chance to ask for a code.
  async function startVerification(isUserInitiated = false) {
    setSending(true);
    setSendError(null);
    setVerifyError(null);
    setOtp('');
    try {
      // The account's real phone number is never carried through router state -- fetched fresh
      // here (this page is only ever reached authenticated, right after register() or login())
      // and handed straight to Firebase, which sends the code itself; this backend never does.
      const settings = await userApi.get();
      setPhoneNumber(settings.phoneNumber);
      const result = await sendPhoneVerificationCode(settings.phoneNumber, RECAPTCHA_CONTAINER_ID);
      setConfirmation(result);
    } catch (err: any) {
      // Logged, not just displayed -- a Firebase Auth error here (e.g. auth/too-many-requests,
      // auth/invalid-app-credential) previously vanished into one generic string with zero trace
      // anywhere, making the actual failure pattern undiagnosable from outside one user's own
      // browser. reportHandledError is a no-op with no Sentry DSN configured.
      console.error('VerifyPhone: startVerification failed', err);
      reportHandledError(err, 'verify-phone-send-otp');
      // Same fix ChangePasswordModal.tsx already carries: a consumed/expired invisible-reCAPTCHA
      // widget throws auth/argument-error on reuse, so a "Resend" click right after ANY failure
      // needs a fresh verifier or it fails for a reason that has nothing to do with the retry.
      resetPhoneVerification();
      setSendError(friendlySendError(err));
    } finally {
      setSending(false);
      if (isUserInitiated) setResendCooldown(RESEND_COOLDOWN_SECONDS);
    }
  }

  useEffect(() => {
    if (startedRef.current) return;
    startedRef.current = true;
    void startVerification(false);
    return () => resetPhoneVerification();
  }, []);

  // The one escape hatch this page previously had none of: before this, a user whose OTP kept
  // failing (bad Firebase config, exhausted quota, anything) had no way off /verify-phone short of
  // closing the tab -- ProtectedRoute sends any other authenticated route straight back here for as
  // long as phoneVerified stays false. Signing out is always available regardless of why sending
  // failed.
  function handleLogout() {
    logout();
    void navigate('/login');
  }

  async function handleVerify(e: FormEvent) {
    e.preventDefault();
    if (!confirmation) return;
    setVerifyError(null);
    setLoading(true);
    try {
      const idToken = await confirmPhoneVerificationCode(confirmation, otp);
      await phoneApi.verify(idToken);
      setPhoneVerified(true);
      void navigate('/app');
    } catch (err: any) {
      setVerifyError(err.response?.data?.message ?? friendlyFirebaseError(err));
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-bg">
      <div className="bg-card rounded-xl2 p-8 w-full max-w-sm shadow-soft border border-border">
        <div className="flex items-center gap-2 mb-6">
          <span className="w-7 h-7 rounded-lg bg-gradient-to-br from-primary to-primary-dark flex items-center justify-center">
            <Sparkles size={14} className="text-white" strokeWidth={2.5} />
          </span>
          <span className="font-extrabold tracking-wide text-ink">FINORA</span>
        </div>

        <div className="flex items-center gap-2 mb-2">
          <ShieldCheck size={20} className="text-primary" />
          <h1 className="text-2xl font-bold text-ink">Verify your phone</h1>
        </div>
        <p className="text-sm text-muted mb-6 flex items-center gap-1.5">
          {confirmation ? (
            <>Enter the 6-digit code we sent to {phoneNumber ? maskPhone(phoneNumber) : 'your mobile number'}.</>
          ) : sending ? (
            <>
              <Loader2 size={13} className="animate-spin flex-shrink-0" />
              Sending a verification code to your mobile number…
            </>
          ) : (
            'We ran into a problem starting verification.'
          )}
        </p>

        {sendError && (
          <div className="mb-4">
            <p className="text-danger text-sm mb-1.5">{sendError}</p>
            <button
              type="button"
              onClick={handleLogout}
              className="text-xs text-muted hover:text-ink font-medium underline"
            >
              Log out and try again later
            </button>
          </div>
        )}
        {verifyError && <p className="text-danger text-sm mb-4">{verifyError}</p>}

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
          onClick={() => startVerification(true)}
          disabled={sending || resendCooldown > 0}
          className="w-full mt-3 text-xs text-primary font-medium text-center disabled:text-muted disabled:cursor-not-allowed"
        >
          {sending
            ? 'Sending…'
            : resendCooldown > 0
              ? `${sendError ? 'Try again' : 'Resend'} in ${resendCooldown}s`
              : sendError
                ? 'Try again'
                : "Didn't get a code? Resend"}
        </button>

        {/* Anchor for Firebase's invisible reCAPTCHA -- never visibly rendered, but must exist in
            the DOM before sendPhoneVerificationCode() runs. */}
        <div id={RECAPTCHA_CONTAINER_ID} />
      </div>
    </div>
  );
}
