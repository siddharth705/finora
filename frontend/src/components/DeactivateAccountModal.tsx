import { useState } from 'react';
import { X } from 'lucide-react';
import { accountLifecycleApi } from '../api/endpoints';
import { GoogleReauthPrompt } from './GoogleReauthPrompt';

// Mirrors User.DEACTIVATION_REASONS on the backend -- the DB CHECK constraint (V88) is the actual
// source of truth for the allowed set, so this is a display-label mapping, not a second copy of
// the validation rule.
const DEACTIVATION_REASONS: { value: string; label: string }[] = [
  { value: 'TAKING_A_BREAK', label: 'Taking a break' },
  { value: 'NOT_USING_ANYMORE', label: "I'm not using it anymore" },
  { value: 'PRIVACY_CONCERNS', label: 'Privacy concerns' },
  { value: 'USING_ANOTHER_APP', label: "I'm using another app" },
  { value: 'OTHER', label: 'Other' },
];

/**
 * Reversible: current password only (no OTP) -- the caller already holds a valid session and can
 * undo this by simply signing in again (see ReactivateAccountPrompt.tsx), unlike the permanent
 * Delete Account flow (Phase B), which reuses the OTP-gated ChangePasswordModal pattern instead.
 * `onDeactivated` is responsible for clearing the local session and redirecting -- there is
 * nothing left to be signed in to once this succeeds, since UserAccountLifecycleService.deactivate
 * already revoked every refresh token server-side.
 */
export function DeactivateAccountModal({ onClose, onDeactivated, signInMethod }: {
  onClose: () => void;
  onDeactivated: () => void;
  signInMethod: 'PASSWORD' | 'GOOGLE';
}) {
  const [currentPassword, setCurrentPassword] = useState('');
  const [reason, setReason] = useState('');
  const [note, setNote] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Shared by both re-auth paths -- the actual deactivate() call is identical either way, only
  // which credential proves it differs. See ChangePasswordModal's identical startWithCredential.
  // Guards against a double-submit itself (rather than relying only on a disabled button) since
  // Google's own rendered button, unlike the password path's <button disabled={submitting}>, has
  // no prop this component can use to stop a second click from firing a second credential
  // callback while the first request is still in flight.
  async function submitWithCredential(currentPasswordArg: string | null, googleIdToken: string | null) {
    if (submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      await accountLifecycleApi.deactivate(currentPasswordArg, googleIdToken, reason, note.trim() || undefined);
      onDeactivated();
    } catch (err: any) {
      setError(err.response?.data?.message ?? 'Could not deactivate your account. Please try again.');
      setSubmitting(false);
    }
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    // Bug fix (review): the Reason <select> is a native form control inside this same <form>, so
    // pressing Enter while it has focus fires implicit form submission regardless of
    // signInMethod. For a GOOGLE account there is no password field to check (and never will be
    // -- see the JSX below), so falling through to the PASSWORD-only validation below showed
    // "Enter your current password" to a user with no such field on screen. The real submission
    // for a GOOGLE account happens through GoogleReauthPrompt's own onCredential callback, not
    // through this form at all.
    if (signInMethod === 'GOOGLE') return;
    if (currentPassword.length === 0) { setError('Enter your current password.'); return; }
    if (reason.length === 0) { setError('Choose a reason for deactivating.'); return; }
    void submitWithCredential(currentPassword, null);
  }

  return (
    <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-30" onClick={onClose} data-testid="deactivate-account-modal">
      <div className="bg-card rounded-xl2 shadow-card p-6 w-[420px] max-w-[90vw]" onClick={(e) => e.stopPropagation()}>
        <div className="flex items-center justify-between mb-4">
          <h2 className="font-serif text-lg font-semibold text-ink">Deactivate Account</h2>
          <button onClick={onClose} className="text-muted hover:text-ink" aria-label="Close">
            <X size={18} />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="space-y-3">
          <p className="text-xs text-muted">
            You'll be signed out everywhere and won't be able to sign back in until you reactivate.
            Your data is retained securely — nothing is deleted, and you can reactivate any time
            just by signing in again.
          </p>
          {signInMethod === 'PASSWORD' && (
            <div>
              <label htmlFor="deactivate-current-password" className="block text-xs uppercase text-muted mb-1">
                Current password
              </label>
              <input
                id="deactivate-current-password"
                type="password"
                autoComplete="current-password"
                value={currentPassword}
                onChange={(e) => { setCurrentPassword(e.target.value); setError(null); }}
                className="bg-card text-ink w-full border border-border rounded-lg px-3 py-2 text-sm"
              />
            </div>
          )}
          <div>
            <label htmlFor="deactivate-reason" className="block text-xs uppercase text-muted mb-1">
              Reason
            </label>
            <select
              id="deactivate-reason"
              value={reason}
              onChange={(e) => { setReason(e.target.value); setError(null); }}
              className="bg-card text-ink w-full border border-border rounded-lg px-3 py-2 text-sm"
            >
              <option value="" disabled>Choose a reason…</option>
              {DEACTIVATION_REASONS.map((r) => (
                <option key={r.value} value={r.value}>{r.label}</option>
              ))}
            </select>
          </div>
          <div>
            <label htmlFor="deactivate-note" className="block text-xs uppercase text-muted mb-1">
              Anything else? (optional)
            </label>
            <textarea
              id="deactivate-note"
              value={note}
              onChange={(e) => setNote(e.target.value)}
              maxLength={500}
              rows={2}
              className="bg-card text-ink w-full border border-border rounded-lg px-3 py-2 text-sm resize-none"
            />
          </div>
          {error && <p className="text-danger text-xs">{error}</p>}

          {signInMethod === 'GOOGLE' && (
            reason.length === 0 ? (
              <p className="text-xs text-muted">Choose a reason above, then verify with Google to continue.</p>
            ) : (
              <GoogleReauthPrompt
                onCredential={(idToken) => submitWithCredential(null, idToken)}
                onError={setError}
              />
            )
          )}

          <div className="flex justify-end gap-2 pt-2">
            <button
              type="button"
              onClick={onClose}
              disabled={submitting}
              className="border border-border rounded-lg px-4 py-2 text-xs uppercase font-medium text-ink hover:bg-black/5 disabled:opacity-50"
            >
              Cancel
            </button>
            {signInMethod === 'PASSWORD' && (
              <button
                type="submit"
                disabled={submitting}
                className="border border-warning text-warning hover:bg-warning-bg rounded-lg px-4 py-2 text-xs uppercase font-medium disabled:opacity-50"
              >
                {submitting ? 'Deactivating…' : 'Deactivate Account'}
              </button>
            )}
          </div>
        </form>
      </div>
    </div>
  );
}
