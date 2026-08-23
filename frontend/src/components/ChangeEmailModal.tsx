import { useState } from 'react';
import { X, Eye, EyeOff, MailCheck } from 'lucide-react';
import { emailChangeApi } from '../api/endpoints';
import { GoogleReauthPrompt } from './GoogleReauthPrompt';

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/**
 * Phase 4 (docs/proposals/authentication-account-security-review.md §2.7). The step-up-gated
 * Change Email flow's first half: verify current identity, enter the new address, and start the
 * session. Unlike ChangePasswordModal/VerifyPhone.tsx, this modal never reaches a "verified,
 * commit now" step itself -- verify()/complete() run on VerifyEmailChange.tsx, reached from the
 * link this backend emails to the NEW address, since that's what proves control of it (there's no
 * in-app OTP to type here the way phone-change/password-change have one). This modal's job ends
 * at "we sent a link" -- see that page for the rest of the flow.
 *
 * currentPassword/googleIdToken/appleIdToken mirror ChangePasswordModal's identical step-up
 * shape exactly -- appleIdToken is always null from this web client (see GoogleReauthPrompt's own
 * doc comment on why Apple Sign-In has no web counterpart here).
 */
export function ChangeEmailModal({ onClose, signInMethod }: {
  onClose: () => void; signInMethod: 'PASSWORD' | 'GOOGLE';
}) {
  const [step, setStep] = useState<'form' | 'sent'>('form');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [currentPassword, setCurrentPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [newEmail, setNewEmail] = useState('');

  const [sentToEmail, setSentToEmail] = useState('');
  const [devVerifyLink, setDevVerifyLink] = useState<string | null>(null);

  const emailValid = EMAIL_PATTERN.test(newEmail.trim());

  async function startWithCredential(currentPasswordArg: string | null, googleIdToken: string | null) {
    // Same double-submit guard as ChangePasswordModal.startWithCredential -- Google's own
    // rendered button has no disabled prop to gate this the way the password path's
    // <button disabled={submitting}> does.
    if (submitting || !emailValid) return;
    setSubmitting(true);
    setError(null);
    try {
      const res = await emailChangeApi.start(currentPasswordArg, googleIdToken, null, newEmail.trim());
      setSentToEmail(newEmail.trim());
      setDevVerifyLink(res.devVerifyLink);
      setStep('sent');
    } catch (e: any) {
      setError(e.response?.data?.message ?? 'Could not start the email change. Please try again.');
    } finally {
      setSubmitting(false);
    }
  }

  function submitCurrentPassword() {
    if (currentPassword.length === 0 || !emailValid || submitting) return;
    void startWithCredential(currentPassword, null);
  }

  return (
    <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-30" onClick={step === 'sent' ? undefined : onClose}>
      <div className="bg-card rounded-xl2 shadow-card p-6 w-[420px] max-w-[90vw]" onClick={(e) => e.stopPropagation()}>
        {step === 'sent' ? (
          <div className="text-center py-4">
            <MailCheck size={32} className="text-success mx-auto mb-3" />
            <p className="text-ink font-medium">Check your inbox</p>
            <p className="text-muted text-sm mt-1">
              We sent a confirmation link to {sentToEmail}. Click it to finish changing your email.
            </p>
            {/* devVerifyLink mirrors ChangePasswordModal's own environment-only affordances --
                populated only when no email provider is configured (see EmailChangeDtos'
                StartResponse doc comment), so this flow stays testable without real email in dev. */}
            {devVerifyLink && (
              <a href={devVerifyLink} className="block mt-3 text-xs text-primary break-all underline">
                {devVerifyLink}
              </a>
            )}
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
              <h2 className="font-serif text-lg font-semibold text-ink">Change Email</h2>
              <button onClick={onClose} className="text-muted hover:text-ink" aria-label="Close">
                <X size={18} />
              </button>
            </div>

            <div className="space-y-3">
              <div>
                <label htmlFor="new-email" className="block text-xs uppercase text-muted mb-1">New email address</label>
                <input
                  id="new-email"
                  type="email"
                  value={newEmail}
                  onChange={(e) => { setNewEmail(e.target.value); setError(null); }}
                  className="bg-card text-ink w-full border border-border rounded-lg px-3 py-2 text-sm"
                />
              </div>

              {signInMethod === 'GOOGLE' ? (
                emailValid ? (
                  <GoogleReauthPrompt
                    onCredential={(idToken) => startWithCredential(null, idToken)}
                    onError={setError}
                  />
                ) : (
                  <p className="text-xs text-muted">Enter a new email address to continue.</p>
                )
              ) : (
                <div>
                  <label htmlFor="current-password-email-change" className="block text-xs uppercase text-muted mb-1">
                    Current password
                  </label>
                  <div className="relative">
                    <input
                      id="current-password-email-change"
                      type={showPassword ? 'text' : 'password'}
                      value={currentPassword}
                      onChange={(e) => setCurrentPassword(e.target.value)}
                      className="bg-card text-ink w-full border border-border rounded-lg px-3 py-2 text-sm pr-9"
                    />
                    <button
                      type="button"
                      onClick={() => setShowPassword((v) => !v)}
                      className="absolute right-2.5 top-1/2 -translate-y-1/2 text-muted hover:text-ink"
                      aria-label={showPassword ? 'Hide current password' : 'Show current password'}
                    >
                      {showPassword ? <EyeOff size={16} /> : <Eye size={16} />}
                    </button>
                  </div>
                </div>
              )}

              {error && <p className="text-danger text-xs">{error}</p>}

              {signInMethod === 'PASSWORD' && (
                <div className="flex items-center justify-end gap-3 pt-3 border-t border-border">
                  <button onClick={onClose} className="text-muted hover:text-ink text-xs uppercase font-medium px-3 py-2">
                    Cancel
                  </button>
                  <button
                    onClick={submitCurrentPassword}
                    disabled={currentPassword.length === 0 || !emailValid || submitting}
                    className="bg-primary text-on-primary hover:bg-primary-dark disabled:opacity-50 rounded-lg px-4 py-2 text-xs uppercase font-medium"
                  >
                    {submitting ? 'Sending…' : 'Send confirmation link'}
                  </button>
                </div>
              )}
            </div>
          </>
        )}
      </div>
    </div>
  );
}
