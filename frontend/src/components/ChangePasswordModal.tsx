import { useEffect, useState } from 'react';
import { X, Eye, EyeOff, CheckCircle2, Circle, ChevronDown, ChevronUp } from 'lucide-react';
import { passwordChangeApi } from '../api/endpoints';
import {
  sendPhoneVerificationCode,
  confirmPhoneVerificationCode,
  resetPhoneVerification,
  friendlySendError,
} from '../lib/phoneAuth';
import { reportHandledError } from '../lib/monitoring';
import { GoogleReauthPrompt } from './GoogleReauthPrompt';
import type { ConfirmationResult } from 'firebase/auth';

const RECAPTCHA_CONTAINER_ID = 'change-password-recaptcha';

function friendlyFirebaseError(err: any): string {
  switch (err?.code) {
    case 'auth/invalid-verification-code':
      return "That code doesn't match — check and try again.";
    case 'auth/code-expired':
      return 'This code has expired. Start over to get a new one.';
    default:
      return 'Could not verify that code. Please try again.';
  }
}

/**
 * The authenticated, OTP-gated Change Password flow (Phase 2) -- current password -> OTP sent to
 * the phone on file -> new password, each step validated server-side against a persisted session
 * (see backend PasswordChangeService) rather than trusted from whatever step this component
 * thinks it's on. Replaces the earlier single-step version.
 *
 * Deliberately still a completely separate journey from Forgot Password (Login -> ForgotPassword
 * -> ResetPassword): that flow assumes the user CAN'T log in (proof of identity via email/OTP);
 * this assumes they already are logged in and know their current password.
 *
 * Unlike the earlier version, the device completing this flow is NEVER logged out -- the
 * "sign out other devices" choice on the last step only ever affects OTHER sessions (see
 * CompleteRequest.currentRefreshToken's own doc comment on the backend), so there's no forced
 * redirect to /login here; success just closes the modal in place.
 */

type Step = 'password' | 'otp' | 'newPassword' | 'success';

const REQUIREMENTS: { label: string; hint: string; test: (pw: string) => boolean }[] = [
  { label: 'At least 8 characters', hint: 'Make it at least 8 characters long', test: (pw) => pw.length >= 8 },
  { label: 'An uppercase letter', hint: 'Add an uppercase letter', test: (pw) => /[A-Z]/.test(pw) },
  { label: 'A lowercase letter', hint: 'Add a lowercase letter', test: (pw) => /[a-z]/.test(pw) },
  { label: 'A number', hint: 'Add a number', test: (pw) => /[0-9]/.test(pw) },
  { label: 'A special character', hint: 'Add a special character', test: (pw) => /[^A-Za-z0-9]/.test(pw) },
];

// Only length is actually enforced server-side (see AuthDtos.PASSWORD_SIZE_MESSAGE) -- the rest
// of REQUIREMENTS is shown as a strength guide, not a hard gate, so this never blocks a
// technically-valid password the backend would accept just because it lacks a symbol.
function strength(pw: string): { label: string; pct: number; colorClass: string } {
  if (pw.length === 0) return { label: '', pct: 0, colorClass: 'bg-border' };
  const met = REQUIREMENTS.filter((r) => r.test(pw)).length;
  if (met <= 2) return { label: 'Weak', pct: 33, colorClass: 'bg-danger' };
  if (met <= 4) return { label: 'Good', pct: 66, colorClass: 'bg-warning' };
  return { label: 'Strong', pct: 100, colorClass: 'bg-success' };
}

// The first unmet suggestion, phrased as the concrete next step -- "Weak" alone doesn't tell
// anyone what to actually do about it.
function nextSuggestion(pw: string): string | null {
  const unmet = REQUIREMENTS.find((r) => !r.test(pw));
  return unmet ? `${unmet.hint} to improve strength.` : null;
}

function PasswordField({ id, label, value, onChange, show, onToggleShow }: {
  id: string; label: string; value: string; onChange: (v: string) => void; show: boolean; onToggleShow: () => void;
}) {
  return (
    <div>
      <label htmlFor={id} className="block text-xs uppercase text-muted mb-1">{label}</label>
      <div className="relative">
        <input
          id={id}
          type={show ? 'text' : 'password'}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          className="bg-card text-ink w-full border border-border rounded-lg px-3 py-2 text-sm pr-9"
        />
        <button
          type="button"
          onClick={onToggleShow}
          className="absolute right-2.5 top-1/2 -translate-y-1/2 text-muted hover:text-ink"
          aria-label={show ? `Hide ${label.toLowerCase()}` : `Show ${label.toLowerCase()}`}
        >
          {show ? <EyeOff size={16} /> : <Eye size={16} />}
        </button>
      </div>
    </div>
  );
}

export function ChangePasswordModal({ onClose, onSuccess, signInMethod }: {
  onClose: () => void; onSuccess?: () => void; signInMethod: 'PASSWORD' | 'GOOGLE';
}) {
  const [step, setStep] = useState<Step>('password');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [currentPassword, setCurrentPassword] = useState('');
  const [showCurrent, setShowCurrent] = useState(false);

  const [sessionId, setSessionId] = useState<string | null>(null);
  const [maskedPhone, setMaskedPhone] = useState('');
  const [confirmation, setConfirmation] = useState<ConfirmationResult | null>(null);
  const [otp, setOtp] = useState('');

  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [showNew, setShowNew] = useState(false);
  const [showRecommendations, setShowRecommendations] = useState(false);
  const [signOutOtherDevices, setSignOutOtherDevices] = useState(true);
  const [successMessage, setSuccessMessage] = useState('');

  useEffect(() => () => resetPhoneVerification(), []);

  const s = strength(newPassword);
  const suggestion = nextSuggestion(newPassword);
  const confirmMismatch = confirmPassword.length > 0 && confirmPassword !== newPassword;
  const otpValid = /^\d{6}$/.test(otp);
  const canSubmitNewPassword = newPassword.length >= 8 && newPassword === confirmPassword && !submitting;

  /** Shared by both re-auth paths below -- once EITHER a current password or a fresh Google
   *  credential has been supplied, everything from here (open the session, send the phone OTP)
   *  is identical regardless of which one it was. */
  async function startWithCredential(currentPasswordArg: string | null, googleIdToken: string | null) {
    // Google's own rendered button has no prop to disable it while a request is in flight
    // (unlike the password path's <button disabled={submitting}>), so this guards against a
    // double-click firing a second concurrent request itself.
    if (submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      const res = await passwordChangeApi.start(currentPasswordArg, googleIdToken);
      setSessionId(res.sessionId);
      setMaskedPhone(res.maskedPhone);
      // Firebase sends the code itself, directly to the phone number this response reveals --
      // this backend never does (see PasswordChangeService.start's own doc comment).
      const result = await sendPhoneVerificationCode(res.phoneNumber, RECAPTCHA_CONTAINER_ID);
      setConfirmation(result);
      setStep('otp');
    } catch (e: any) {
      // Logged, not just displayed -- a Firebase Auth error (e.g. sendPhoneVerificationCode
      // throwing auth/too-many-requests, auth/invalid-app-credential) has no response.data.message
      // and previously vanished into the generic fallback text with zero trace anywhere, making
      // this exact failure mode undiagnosable from the browser alone. A backend rejection (e.g.
      // wrong current password) already carries its own message via e.response and needs no
      // Firebase-specific logging.
      console.error('ChangePasswordModal: startWithCredential failed', e);
      if (!e.response) {
        reportHandledError(e, 'change-password-send-otp');
      }
      // Bug fix: getRecaptchaVerifier() caches one RecaptchaVerifier instance at module scope and
      // previously only cleared it when the modal unmounted -- so retrying "Send code" in place
      // after ANY failure (wrong password, a network blip, anything) reused an
      // already-consumed/expired invisible-reCAPTCHA widget on the next attempt, which
      // signInWithPhoneNumber rejects with auth/argument-error regardless of whether the retry's
      // credential and phone number were perfectly valid. Resetting here is harmless when the
      // failure happened before sendPhoneVerificationCode ever ran (e.g. a wrong-password 400
      // from passwordChangeApi.start()) -- resetPhoneVerification() is a no-op on an
      // already-null/unused verifier -- so it's safe to call unconditionally on any failure here.
      resetPhoneVerification();
      setError(e.response?.data?.message ?? friendlySendError(e));
    } finally {
      setSubmitting(false);
    }
  }

  function submitCurrentPassword() {
    if (currentPassword.length === 0 || submitting) return;
    void startWithCredential(currentPassword, null);
  }

  async function submitOtp() {
    if (!sessionId || !confirmation || !otpValid || submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      const firebaseIdToken = await confirmPhoneVerificationCode(confirmation, otp);
      await passwordChangeApi.verifyOtp(sessionId, firebaseIdToken);
      setStep('newPassword');
    } catch (e: any) {
      console.error('ChangePasswordModal: submitOtp failed', e);
      setError(e.response?.data?.message ?? friendlyFirebaseError(e));
      setOtp('');
    } finally {
      setSubmitting(false);
    }
  }

  // Starting over re-verifies the current password and issues a fresh code -- there's no
  // separate "resend" endpoint, since a new code always requires re-proving current-password
  // ownership first (see PasswordChangeService.start's own doc comment).
  function startOver() {
    setStep('password');
    setOtp('');
    setSessionId(null);
    setConfirmation(null);
    resetPhoneVerification();
    setError(null);
  }

  async function submitNewPassword() {
    if (!sessionId || !canSubmitNewPassword) return;
    // BH-012: no refresh token to look up, and no "your session information is missing" branch --
    // that error existed only because this read a value out of localStorage that might not be
    // there. The session is identified server-side from the access token this request already
    // carries.
    setSubmitting(true);
    setError(null);
    try {
      const res = await passwordChangeApi.complete(sessionId, newPassword, signOutOtherDevices);
      setSuccessMessage(res.message);
      setStep('success');
      onSuccess?.();
    } catch (e: any) {
      console.error('ChangePasswordModal: submitNewPassword failed', e);
      setError(e.response?.data?.message ?? 'Could not update your password. Please try again.');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-30" onClick={step === 'success' ? undefined : onClose}>
      <div className="bg-card rounded-xl2 shadow-card p-6 w-[420px] max-w-[90vw]" onClick={(e) => e.stopPropagation()}>
        {step === 'success' ? (
          <div className="text-center py-4">
            <CheckCircle2 size={32} className="text-success mx-auto mb-3" />
            <p className="text-ink font-medium">Password updated</p>
            <p className="text-muted text-sm mt-1">{successMessage}</p>
            <button
              onClick={onClose}
              className="mt-5 bg-primary text-on-primary hover:bg-primary-dark rounded-lg px-4 py-2 text-xs uppercase font-medium"
            >
              Done
            </button>
          </div>
        ) : (
          <>
            <div className="flex items-center justify-between mb-4">
              <h2 className="text-lg font-semibold text-ink">Change Password</h2>
              <button onClick={onClose} className="text-muted hover:text-ink" aria-label="Close">
                <X size={18} />
              </button>
            </div>

            {/* Anchor for Firebase's invisible reCAPTCHA -- never visibly rendered, but must exist
                in the DOM before sendPhoneVerificationCode() runs. Bug fix: this used to be
                rendered only inside the 'otp' step's JSX, but sendPhoneVerificationCode() is
                actually called from submitCurrentPassword() while step is still 'password' --
                the container element didn't exist yet at the moment RecaptchaVerifier needed it,
                so signInWithPhoneNumber() failed with auth/argument-error on every attempt, not
                just retries. Rendered unconditionally here (outside any step-specific block) so
                it's always present for the whole time the modal is mounted. */}
            <div id={RECAPTCHA_CONTAINER_ID} />

            {step === 'password' && signInMethod === 'GOOGLE' && (
              <div className="space-y-3">
                <GoogleReauthPrompt
                  onCredential={(idToken) => startWithCredential(null, idToken)}
                  onError={setError}
                />
                {error && <p className="text-danger text-xs">{error}</p>}
                <div className="flex items-center justify-end pt-3 border-t border-border">
                  <button onClick={onClose} className="text-muted hover:text-ink text-xs uppercase font-medium px-3 py-2">
                    Cancel
                  </button>
                </div>
              </div>
            )}

            {step === 'password' && signInMethod === 'PASSWORD' && (
              <div className="space-y-3">
                <p className="text-xs text-muted">Enter your current password to get started. We'll send a verification code to the phone on file.</p>
                <PasswordField
                  id="current-password" label="Current password" value={currentPassword} onChange={setCurrentPassword}
                  show={showCurrent} onToggleShow={() => setShowCurrent((v) => !v)}
                />
                {error && <p className="text-danger text-xs">{error}</p>}
                <div className="flex items-center justify-end gap-3 pt-3 border-t border-border">
                  <button onClick={onClose} className="text-muted hover:text-ink text-xs uppercase font-medium px-3 py-2">
                    Cancel
                  </button>
                  <button
                    onClick={submitCurrentPassword}
                    disabled={currentPassword.length === 0 || submitting}
                    className="bg-primary text-on-primary hover:bg-primary-dark disabled:opacity-50 rounded-lg px-4 py-2 text-xs uppercase font-medium"
                  >
                    {submitting ? 'Sending…' : 'Send code'}
                  </button>
                </div>
              </div>
            )}

            {step === 'otp' && (
              <div className="space-y-3">
                <p className="text-xs text-muted">Enter the 6-digit code sent to {maskedPhone}.</p>
                <input
                  value={otp}
                  onChange={(e) => { setOtp(e.target.value.replace(/\D/g, '').slice(0, 6)); setError(null); }}
                  inputMode="numeric"
                  placeholder="123456"
                  aria-label="Verification code"
                  disabled={!confirmation}
                  className="bg-white text-gray-900 w-full border border-border rounded-lg px-3 py-2.5 text-center text-lg tracking-[0.4em] font-mono focus:outline-none focus:ring-2 focus:ring-primary/30 disabled:opacity-50"
                />
                {error && <p className="text-danger text-xs">{error}</p>}
                <div className="flex items-center justify-between pt-3 border-t border-border">
                  <button type="button" onClick={startOver} className="text-primary text-[11px] font-medium">
                    Didn't get a code? Start over
                  </button>
                  <button
                    onClick={submitOtp}
                    disabled={!otpValid || submitting}
                    className="bg-primary text-on-primary hover:bg-primary-dark disabled:opacity-50 rounded-lg px-4 py-2 text-xs uppercase font-medium"
                  >
                    {submitting ? 'Verifying…' : 'Verify'}
                  </button>
                </div>
              </div>
            )}

            {step === 'newPassword' && (
              <div className="space-y-3">
                <PasswordField
                  id="new-password" label="New password" value={newPassword} onChange={setNewPassword}
                  show={showNew} onToggleShow={() => setShowNew((v) => !v)}
                />
                {newPassword.length > 0 && (
                  <div>
                    <div className="h-1.5 rounded-full bg-border overflow-hidden">
                      <div className={`h-full ${s.colorClass}`} style={{ width: `${s.pct}%` }} />
                    </div>
                    <p className="text-xs text-muted mt-1">
                      {s.label}
                      {suggestion && s.label !== 'Strong' ? ` — ${suggestion}` : ''}
                    </p>
                  </div>
                )}
                <PasswordField
                  id="confirm-new-password" label="Confirm new password" value={confirmPassword} onChange={setConfirmPassword}
                  show={showNew} onToggleShow={() => setShowNew((v) => !v)}
                />
                {confirmMismatch && <p className="text-danger text-xs">Passwords don't match.</p>}

                <div className="bg-bg rounded-lg border border-border px-3 py-2.5">
                  <button
                    type="button"
                    onClick={() => setShowRecommendations((v) => !v)}
                    className="w-full flex items-center justify-between text-xs uppercase text-muted"
                  >
                    Recommended for a stronger password
                    {showRecommendations ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
                  </button>
                  {showRecommendations && (
                    <ul className="space-y-1 mt-1.5">
                      {REQUIREMENTS.map((r) => {
                        const met = r.test(newPassword);
                        return (
                          <li key={r.label} className={`text-xs flex items-center gap-1.5 ${met ? 'text-success' : 'text-muted'}`}>
                            {met ? <CheckCircle2 size={12} /> : <Circle size={12} />}
                            {r.label}
                          </li>
                        );
                      })}
                    </ul>
                  )}
                </div>

                <div className="border border-border rounded-lg px-3 py-2.5 space-y-2">
                  <p className="text-xs uppercase text-muted">Other devices</p>
                  <label className="flex items-start gap-2 text-xs text-ink cursor-pointer">
                    <input
                      type="radio"
                      name="signOutOtherDevices"
                      checked={signOutOtherDevices}
                      onChange={() => setSignOutOtherDevices(true)}
                      className="mt-0.5"
                    />
                    <span>Sign out other devices <span className="text-muted">(Recommended)</span> — this device stays signed in.</span>
                  </label>
                  <label className="flex items-start gap-2 text-xs text-ink cursor-pointer">
                    <input
                      type="radio"
                      name="signOutOtherDevices"
                      checked={!signOutOtherDevices}
                      onChange={() => setSignOutOtherDevices(false)}
                      className="mt-0.5"
                    />
                    <span>Keep other devices signed in</span>
                  </label>
                </div>

                {error && <p className="text-danger text-xs">{error}</p>}

                <div className="flex items-center justify-end gap-3 pt-3 border-t border-border">
                  <button onClick={onClose} className="text-muted hover:text-ink text-xs uppercase font-medium px-3 py-2">
                    Cancel
                  </button>
                  <button
                    onClick={submitNewPassword}
                    disabled={!canSubmitNewPassword}
                    className="bg-primary text-on-primary hover:bg-primary-dark disabled:opacity-50 rounded-lg px-4 py-2 text-xs uppercase font-medium"
                  >
                    {submitting ? 'Updating…' : 'Update Password'}
                  </button>
                </div>
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
}
