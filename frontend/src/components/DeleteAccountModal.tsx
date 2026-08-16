import { useEffect, useState } from 'react';
import { X, AlertTriangle } from 'lucide-react';
import { passwordChangeApi, accountLifecycleApi } from '../api/endpoints';
import { sendPhoneVerificationCode, confirmPhoneVerificationCode, resetPhoneVerification } from '../lib/phoneAuth';
import type { ConfirmationResult } from 'firebase/auth';

const RECAPTCHA_CONTAINER_ID = 'delete-account-recaptcha';

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
 * Permanent, irreversible: current-password + phone OTP gated -- the same bar as
 * ChangePasswordModal, whose password -> otp steps this forks verbatim (same session-backed
 * passwordChangeApi.start/verifyOtp calls). Diverges after that: no new password, just a final
 * danger-styled confirm step calling accountLifecycleApi.deleteAccount(sessionId), which consumes
 * the session server-side (PasswordChangeService.consumeForAccountDeletion) rather than completing
 * a password change.
 *
 * There is deliberately no cancel link anywhere in this flow, matching the backend's product
 * decision (UserAccountLifecycleService.requestDeletion's own doc comment) -- the 48h purge delay
 * is an ops safety margin, not a user-facing undo. `onDeleted` is responsible for clearing the
 * local session and redirecting, same as DeactivateAccountModal's `onDeactivated` -- there is
 * nothing left to be signed in to once this succeeds, since requestDeletion already revoked every
 * refresh token server-side.
 */

type Step = 'password' | 'otp' | 'confirm';

export function DeleteAccountModal({ onClose, onDeleted }: {
  onClose: () => void;
  onDeleted: () => void;
}) {
  const [step, setStep] = useState<Step>('password');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [currentPassword, setCurrentPassword] = useState('');

  const [sessionId, setSessionId] = useState<string | null>(null);
  const [maskedPhone, setMaskedPhone] = useState('');
  const [confirmation, setConfirmation] = useState<ConfirmationResult | null>(null);
  const [otp, setOtp] = useState('');

  const [understood, setUnderstood] = useState(false);

  useEffect(() => () => resetPhoneVerification(), []);

  const otpValid = /^\d{6}$/.test(otp);

  async function submitCurrentPassword() {
    if (currentPassword.length === 0 || submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      const res = await passwordChangeApi.start(currentPassword);
      setSessionId(res.sessionId);
      setMaskedPhone(res.maskedPhone);
      const result = await sendPhoneVerificationCode(res.phoneNumber, RECAPTCHA_CONTAINER_ID);
      setConfirmation(result);
      setStep('otp');
    } catch (e: any) {
      console.error('DeleteAccountModal: submitCurrentPassword failed', e);
      // See ChangePasswordModal's identical call for why this is unconditional on any failure here.
      resetPhoneVerification();
      setError(e.response?.data?.message ?? 'Could not start account deletion. Please try again.');
    } finally {
      setSubmitting(false);
    }
  }

  async function submitOtp() {
    if (!sessionId || !confirmation || !otpValid || submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      const firebaseIdToken = await confirmPhoneVerificationCode(confirmation, otp);
      await passwordChangeApi.verifyOtp(sessionId, firebaseIdToken);
      setStep('confirm');
    } catch (e: any) {
      console.error('DeleteAccountModal: submitOtp failed', e);
      setError(e.response?.data?.message ?? friendlyFirebaseError(e));
      setOtp('');
    } finally {
      setSubmitting(false);
    }
  }

  function startOver() {
    setStep('password');
    setOtp('');
    setSessionId(null);
    setConfirmation(null);
    resetPhoneVerification();
    setError(null);
  }

  async function submitDelete() {
    if (!sessionId || !understood || submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      await accountLifecycleApi.deleteAccount(sessionId);
      onDeleted();
    } catch (e: any) {
      console.error('DeleteAccountModal: submitDelete failed', e);
      setError(e.response?.data?.message ?? 'Could not delete your account. Please try again.');
      setSubmitting(false);
    }
  }

  return (
    <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-30" onClick={onClose}>
      <div className="bg-card rounded-xl2 shadow-card p-6 w-[420px] max-w-[90vw]" onClick={(e) => e.stopPropagation()}>
        <div className="flex items-center justify-between mb-4">
          <h2 className="font-serif text-lg font-semibold text-ink">Delete Account</h2>
          <button onClick={onClose} className="text-muted hover:text-ink" aria-label="Close">
            <X size={18} />
          </button>
        </div>

        {/* See ChangePasswordModal's identical anchor for why this must be rendered unconditionally,
            not just inside the 'otp' step's JSX. */}
        <div id={RECAPTCHA_CONTAINER_ID} />

        {step === 'password' && (
          <div className="space-y-3">
            <p className="text-xs text-muted">
              Deleting your account is permanent and cannot be undone. Enter your current password
              to get started -- we'll send a verification code to the phone on file.
            </p>
            <div>
              <label htmlFor="delete-current-password" className="block text-xs uppercase text-muted mb-1">
                Current password
              </label>
              <input
                id="delete-current-password"
                type="password"
                autoComplete="current-password"
                value={currentPassword}
                onChange={(e) => { setCurrentPassword(e.target.value); setError(null); }}
                className="bg-card text-ink w-full border border-border rounded-lg px-3 py-2 text-sm"
              />
            </div>
            {error && <p className="text-danger text-xs">{error}</p>}
            <div className="flex items-center justify-end gap-3 pt-3 border-t border-border">
              <button onClick={onClose} className="text-muted hover:text-ink text-xs uppercase font-medium px-3 py-2">
                Cancel
              </button>
              <button
                onClick={submitCurrentPassword}
                disabled={currentPassword.length === 0 || submitting}
                className="border border-danger text-danger hover:bg-danger-bg disabled:opacity-50 rounded-lg px-4 py-2 text-xs uppercase font-medium"
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
                className="border border-danger text-danger hover:bg-danger-bg disabled:opacity-50 rounded-lg px-4 py-2 text-xs uppercase font-medium"
              >
                {submitting ? 'Verifying…' : 'Verify'}
              </button>
            </div>
          </div>
        )}

        {step === 'confirm' && (
          <div className="space-y-3">
            <div className="bg-danger-bg border border-danger rounded-lg px-3 py-2.5 flex gap-2.5">
              <AlertTriangle size={16} className="text-danger flex-shrink-0 mt-0.5" />
              <div className="text-xs text-ink space-y-1.5">
                <p className="font-medium">This cannot be undone.</p>
                <p>
                  Your account is permanently deleted in 48 hours. You'll be signed out everywhere
                  immediately and won't be able to sign back in during that window -- there is no
                  way to cancel this request once submitted.
                </p>
              </div>
            </div>
            <label className="flex items-start gap-2 text-xs text-ink cursor-pointer">
              <input
                type="checkbox"
                checked={understood}
                onChange={(e) => setUnderstood(e.target.checked)}
                className="mt-0.5"
              />
              <span>I understand this permanently deletes my account and cannot be undone.</span>
            </label>
            {error && <p className="text-danger text-xs">{error}</p>}
            <div className="flex items-center justify-end gap-3 pt-3 border-t border-border">
              <button onClick={onClose} className="text-muted hover:text-ink text-xs uppercase font-medium px-3 py-2">
                Cancel
              </button>
              <button
                onClick={submitDelete}
                disabled={!understood || submitting}
                className="bg-danger text-white hover:bg-danger/90 disabled:opacity-50 rounded-lg px-4 py-2 text-xs uppercase font-medium"
              >
                {submitting ? 'Deleting…' : 'Permanently Delete Account'}
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
