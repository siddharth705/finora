import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { Sparkles, ShieldCheck, Loader2 } from 'lucide-react';
import { phoneApi, phoneChangeApi, userApi } from '../api/endpoints';
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

// Same convention Register.tsx's own mobile-number field uses -- a fixed "+91" prefix shown next
// to the field rather than typed into it, so these only ever need to sanitize the 10-digit local
// number. Duplicated here rather than imported: Register.tsx doesn't export these, and each is a
// few lines with no shared state, the same reasoning PasswordChangeService/AuthService already
// apply to their own small, page-local phoneNumbersMatch() duplicates.
function sanitizeLocalPhoneNumber(raw: string): string {
  return raw.replace(/[^0-9]/g, '').slice(0, 10);
}

function sanitizePastedPhoneNumber(raw: string): string {
  const digitsOnly = raw.replace(/[^0-9]/g, '');
  const local = digitsOnly.length > 10 && digitsOnly.startsWith('91') ? digitsOnly.slice(2) : digitsOnly;
  return local.slice(0, 10);
}

const PHONE_PATTERN = /^[6-9][0-9]{9}$/;

export default function VerifyPhone() {
  const navigate = useNavigate();
  const location = useLocation();
  // Distinguishes a RETURNING user who still hasn't verified (Login.tsx's own navigate call sets
  // this) from a brand-new registration landing here for the first time (Register.tsx's own
  // identical navigate call never does) -- greeting a fresh signup with "Welcome back" would be
  // backwards.
  const fromLogin = Boolean((location.state as { fromLogin?: boolean } | null)?.fromLogin);
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

  // The "Change Number" detour: for a user whose account phone number is wrong or unreachable
  // (see the sendError escape hatch below), with no other self-service way to fix it. A separate
  // mode on this same page rather than a new route -- the surrounding FYNORA/shield chrome stays
  // put, only the form content changes, and there's no reason to lose the original session
  // (confirmation/phoneNumber above) if the user backs out.
  const [mode, setMode] = useState<'verify' | 'enterNewNumber' | 'confirmNewNumber'>('verify');
  // Distinguishes "this account has no phone number at all yet" (a Google Sign-In account -- see
  // AuthService.createGoogleUserRecord's own doc comment, phoneNumber is left null there) from the
  // ordinary "the number on file is wrong/unreachable" case the Change Number detour was built
  // for. Same form either way (PhoneChangeService.start() now accepts both, see its own doc
  // comment), but the copy and the presence of a "Back" control need to differ: there is no
  // working `verify` state to go back to when there was never a number to verify in the first
  // place.
  const [numberMissing, setNumberMissing] = useState(false);
  const [newLocalNumber, setNewLocalNumber] = useState('');
  const [newNumberTouched, setNewNumberTouched] = useState(false);
  const [changeSessionId, setChangeSessionId] = useState<string | null>(null);
  const [changeMaskedPhone, setChangeMaskedPhone] = useState<string | null>(null);
  const [changeConfirmation, setChangeConfirmation] = useState<ConfirmationResult | null>(null);
  const [changeOtp, setChangeOtp] = useState('');
  const [changeError, setChangeError] = useState<string | null>(null);
  const [changeSubmitting, setChangeSubmitting] = useState(false);
  const [changeResendCooldown, setChangeResendCooldown] = useState(0);

  // Ticks the resend cooldown down to 0 once a second. Only runs while there's actually a
  // cooldown in progress, so this is a no-op for almost the entire life of the page.
  useEffect(() => {
    if (resendCooldown <= 0) return;
    const id = setInterval(() => setResendCooldown((s) => Math.max(0, s - 1)), 1000);
    return () => clearInterval(id);
  }, [resendCooldown]);

  // Same cooldown mechanism, scoped separately to the Change Number sub-flow's own "Send code"
  // button -- a distinct piece of state rather than reusing resendCooldown above, since the two
  // buttons are independent controls that can each be mid-cooldown on their own schedule.
  useEffect(() => {
    if (changeResendCooldown <= 0) return;
    const id = setInterval(() => setChangeResendCooldown((s) => Math.max(0, s - 1)), 1000);
    return () => clearInterval(id);
  }, [changeResendCooldown]);

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
      // Bug fix (review): a Google Sign-In account reaches this page with NO phone number on
      // file at all (AuthService.createGoogleUserRecord leaves it null) -- there is nothing to
      // send a code to yet. Unconditionally calling sendPhoneVerificationCode(null, ...) used to
      // crash Firebase's own SDK with a raw TypeError ("'session' in null") and surface as a
      // generic, unrecoverable-looking "Could not send a verification code" error. Route straight
      // into the same number-entry form the OTP-failure escape hatch below already provides,
      // instead of attempting (and failing) a send with nothing to send to.
      if (!settings.phoneNumber) {
        setNumberMissing(true);
        setMode('enterNewNumber');
        return;
      }
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
    void navigate('/auth');
  }

  function startChangingNumber() {
    setMode('enterNewNumber');
    // Deliberately does NOT touch numberMissing: this is also the handler for confirmNewNumber's
    // own "Didn't get a code? Change number" retry link, which is reachable from EITHER the
    // ordinary escape-hatch entry (numberMissing already false, nothing to do) or the Google
    // Sign-In first-time-set flow (numberMissing already true -- and must stay true, or the form
    // wrongly claims a prior number exists and offers a "Back" button into a `verify` state that
    // was never actually reached, a dead end with no sendError-gated escape hatch to show).
    setNewLocalNumber('');
    setNewNumberTouched(false);
    setChangeError(null);
    // Not carried over from a previous attempt -- a fresh trip into this form is either the
    // first attempt, or the user explicitly asking to target a different number (see "Didn't get
    // a code? Change number" below), neither of which is the repeated-click the cooldown guards
    // against.
    setChangeResendCooldown(0);
  }

  /** Step 1 of the detour: open a PhoneChangeSession server-side, then have Firebase send a code
   *  to the NEW number directly -- same pattern as startVerification() above, just against
   *  phoneChangeApi.start() instead of userApi.get(). */
  async function handleStartPhoneChange(e: FormEvent) {
    e.preventDefault();
    if (!PHONE_PATTERN.test(newLocalNumber)) {
      setNewNumberTouched(true);
      return;
    }
    const requestedNumber = `+91${newLocalNumber}`;
    setChangeSubmitting(true);
    setChangeError(null);
    try {
      const start = await phoneChangeApi.start(requestedNumber);
      const result = await sendPhoneVerificationCode(requestedNumber, RECAPTCHA_CONTAINER_ID);
      setChangeSessionId(start.sessionId);
      setChangeMaskedPhone(start.maskedPhone);
      setChangeConfirmation(result);
      setChangeOtp('');
      setChangeResendCooldown(RESEND_COOLDOWN_SECONDS);
      setMode('confirmNewNumber');
    } catch (err: any) {
      // A rejected number (already claimed, or identical to the one already on file) carries
      // err.response and needs no Firebase-specific logging; a Firebase send failure does not,
      // same distinction ResetPassword.tsx's own requestOtp() catch block makes. It's also the
      // right signal for whether a cooldown belongs here at all -- a backend rejection means the
      // fix is editing the number just typed in, not waiting out a timer before resubmitting the
      // exact same one; only a genuine Firebase-side send failure is the "don't hammer this"
      // case the cooldown exists for.
      if (!err.response) {
        console.error('VerifyPhone: handleStartPhoneChange failed', err);
        reportHandledError(err, 'verify-phone-change-number-send-otp');
        setChangeResendCooldown(RESEND_COOLDOWN_SECONDS);
      }
      resetPhoneVerification();
      setChangeError(err.response?.data?.message ?? friendlySendError(err));
    } finally {
      setChangeSubmitting(false);
    }
  }

  /** Step 2 of the detour: confirm the code against the NEW number, then commit it -- verifyOtp()
   *  proves control of that number server-side, complete() writes it onto the account and marks
   *  phoneVerified, same as the normal verify flow's own phoneApi.verify() does for the original
   *  number. */
  async function handleConfirmPhoneChange(e: FormEvent) {
    e.preventDefault();
    if (!changeConfirmation || !changeSessionId) return;
    setChangeError(null);
    setChangeSubmitting(true);
    try {
      const idToken = await confirmPhoneVerificationCode(changeConfirmation, changeOtp);
      await phoneChangeApi.verifyOtp(changeSessionId, idToken);
      const completed = await phoneChangeApi.complete(changeSessionId);
      setPhoneNumber(completed.phoneNumber);
      setPhoneVerified(true);
      void navigate('/app');
    } catch (err: any) {
      setChangeError(err.response?.data?.message ?? friendlyFirebaseError(err));
    } finally {
      setChangeSubmitting(false);
    }
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
            <Sparkles size={14} className="text-on-primary" strokeWidth={2.5} />
          </span>
          <span className="font-extrabold tracking-wide text-ink">FYNORA</span>
        </div>

        {mode === 'verify' && (
          <>
            {fromLogin && <p className="text-sm font-medium text-ink mb-1">Welcome back!</p>}
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
                <div className="flex items-center gap-3">
                  <button
                    type="button"
                    onClick={startChangingNumber}
                    className="text-xs text-primary font-medium underline"
                  >
                    Change number
                  </button>
                  <span className="text-border">·</span>
                  <button
                    type="button"
                    onClick={handleLogout}
                    className="text-xs text-muted hover:text-ink font-medium underline"
                  >
                    Log out and try again later
                  </button>
                </div>
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
                className="w-full bg-primary hover:bg-primary-dark text-on-primary rounded-lg py-2.5 text-sm font-semibold disabled:opacity-50"
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

            {/* Same trust-box pattern Register.tsx/Login.tsx already use elsewhere in the auth
                flow -- this is the one auth screen that didn't have it, despite being the one
                explicitly asking for a phone number a second time. */}
            <div className="flex items-start gap-2.5 bg-primary-light rounded-lg p-3 mt-6">
              <ShieldCheck size={16} className="text-primary flex-shrink-0 mt-0.5" />
              <p className="text-xs text-ink">
                This number is used only to verify your identity — never for marketing, and never shared.
              </p>
            </div>
          </>
        )}

        {mode === 'enterNewNumber' && (
          <>
            <div className="flex items-center gap-2 mb-2">
              <ShieldCheck size={20} className="text-primary" />
              <h1 className="text-2xl font-bold text-ink">
                {numberMissing ? 'Add your phone number' : 'Change your number'}
              </h1>
            </div>
            <p className="text-sm text-muted mb-4">
              {numberMissing
                ? "Your account doesn't have a mobile number on file yet. We'll send a code to confirm it's yours."
                : "Enter the mobile number you'd like to use instead. We'll send a code to confirm it's yours before updating your account."}
            </p>

            {changeError && <p className="text-danger text-sm mb-4">{changeError}</p>}

            <form onSubmit={handleStartPhoneChange}>
              <label htmlFor="new-phone-number" className="block text-xs font-medium text-muted mb-1">New mobile number</label>
              <div className="relative mb-1">
                <div className="absolute left-3 top-1/2 -translate-y-1/2 flex items-center gap-1.5 text-sm text-ink pointer-events-none select-none">
                  <span aria-hidden="true">🇮🇳</span>
                  <span>+91</span>
                  <span className="w-px h-4 bg-border" />
                </div>
                <input
                  id="new-phone-number"
                  type="tel"
                  inputMode="numeric"
                  value={newLocalNumber}
                  onChange={(e) => setNewLocalNumber(sanitizeLocalPhoneNumber(e.target.value))}
                  onPaste={(e) => {
                    e.preventDefault();
                    setNewLocalNumber(sanitizePastedPhoneNumber(e.clipboardData.getData('text')));
                  }}
                  onBlur={() => setNewNumberTouched(true)}
                  required
                  placeholder="XXXXXXXXXX"
                  maxLength={10}
                  title="10-digit mobile number"
                  className="w-full border border-border rounded-lg pl-[4.75rem] pr-3 py-2.5 text-sm bg-white text-gray-900 focus:outline-none focus:ring-2 focus:ring-primary/30"
                />
              </div>
              <p className="text-[11px] mb-4 h-3.5">
                {newNumberTouched && !PHONE_PATTERN.test(newLocalNumber) && (
                  <span className="text-danger">Enter a valid 10-digit mobile number (no leading 0-5).</span>
                )}
              </p>

              <button
                type="submit"
                disabled={changeSubmitting || changeResendCooldown > 0}
                className="w-full bg-primary hover:bg-primary-dark text-on-primary rounded-lg py-2.5 text-sm font-semibold disabled:opacity-50"
              >
                {changeSubmitting
                  ? 'Sending…'
                  : changeResendCooldown > 0
                    ? `Send code in ${changeResendCooldown}s`
                    : 'Send code'}
              </button>
            </form>

            {numberMissing ? (
              // No "Back" here -- unlike the ordinary Change Number entry, there is no working
              // `verify` mode to return to: this account never had a number to attempt sending a
              // code to in the first place (see startVerification's own comment). Logout is the
              // only real way out if the user doesn't want to add a number right now.
              <button
                type="button"
                onClick={handleLogout}
                className="w-full mt-3 text-xs text-muted hover:text-ink font-medium text-center"
              >
                Log out and try again later
              </button>
            ) : (
              <button
                type="button"
                onClick={() => setMode('verify')}
                className="w-full mt-3 text-xs text-primary font-medium text-center"
              >
                Back
              </button>
            )}
          </>
        )}

        {mode === 'confirmNewNumber' && (
          <>
            <div className="flex items-center gap-2 mb-2">
              <ShieldCheck size={20} className="text-primary" />
              <h1 className="text-2xl font-bold text-ink">Confirm your number</h1>
            </div>
            <p className="text-sm text-muted mb-6">
              Enter the 6-digit code we sent to {changeMaskedPhone ?? 'your new number'}.
            </p>

            {changeError && <p className="text-danger text-sm mb-4">{changeError}</p>}

            <form onSubmit={handleConfirmPhoneChange}>
              <label htmlFor="confirm-new-number-otp" className="block text-xs font-medium text-muted mb-1">Verification code</label>
              <input
                id="confirm-new-number-otp"
                value={changeOtp}
                onChange={(e) => setChangeOtp(e.target.value.replace(/\D/g, '').slice(0, 6))}
                required
                inputMode="numeric"
                placeholder="123456"
                className="bg-white text-gray-900 w-full border border-border rounded-lg px-3 py-2.5 mb-4 text-center text-lg tracking-[0.5em] font-mono focus:outline-none focus:ring-2 focus:ring-primary/30"
              />

              <button
                type="submit"
                disabled={changeSubmitting || changeOtp.length !== 6}
                className="w-full bg-primary hover:bg-primary-dark text-on-primary rounded-lg py-2.5 text-sm font-semibold disabled:opacity-50"
              >
                {changeSubmitting ? 'Confirming…' : 'Confirm number'}
              </button>
            </form>

            <button
              type="button"
              onClick={startChangingNumber}
              className="w-full mt-3 text-xs text-primary font-medium text-center"
            >
              Didn't get a code? Change number
            </button>
          </>
        )}

        {/* Anchor for Firebase's invisible reCAPTCHA -- shared by the original verify flow above
            and the Change Number detour, never visibly rendered, but must exist in the DOM before
            sendPhoneVerificationCode() runs. */}
        <div id={RECAPTCHA_CONTAINER_ID} />
      </div>
    </div>
  );
}
