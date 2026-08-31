import { useState } from 'react';
import type { FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { ShieldAlert } from 'lucide-react';
import { authApi } from '../api/endpoints';

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/**
 * Reuses the exact same /auth/forgot-password endpoint the user-facing app (frontend/) already
 * calls -- there is only one password-reset implementation, this is just the admin portal's own
 * screen for it. Same reasoning as VerifyPhone.tsx reusing /phone/* rather than the admin portal
 * growing a parallel mechanism.
 */
export default function ForgotPassword() {
  const [email, setEmail] = useState('');
  const [touched, setTouched] = useState(false);
  const [submitted, setSubmitted] = useState(false);
  const [devLink, setDevLink] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const emailValid = EMAIL_PATTERN.test(email.trim());

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setTouched(true);
    setError(null);
    if (!emailValid) { setError('Enter a valid email address.'); return; }
    setLoading(true);
    try {
      const res = await authApi.forgotPassword(email.trim());
      setDevLink(res.devResetLink);
      setSubmitted(true);
    } catch (err: any) {
      setError(err.response?.data?.message ?? 'Something went wrong. Try again.');
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="min-h-screen bg-bg flex items-center justify-center p-4">
      <div className="w-full max-w-sm">
        <div className="flex items-center gap-2.5 justify-center mb-8">
          {/* to-[#15171C] rather than to-primary-dark: primary-dark flips to light paper in dark
              mode, which would fade this badge's rose-to-dark gradient into a rose-to-pale one --
              pinning the dark end keeps the white icon readable in both themes. */}
          <div className="w-9 h-9 rounded-lg bg-gradient-to-br from-rose-400 to-[#15171C] flex items-center justify-center">
            <ShieldAlert size={18} className="text-white" strokeWidth={2.5} />
          </div>
          <span className="font-extrabold tracking-wide text-xl text-ink">FYNORA ADMIN</span>
        </div>

        <div className="bg-card border border-border rounded-xl2 shadow-soft p-6">
          {submitted ? (
            <>
              <h1 className="text-lg font-bold text-ink mb-2">Check your email</h1>
              <p className="text-sm text-muted leading-relaxed mb-4">
                If an account exists for <strong>{email.trim()}</strong>, a reset link has been issued.
              </p>
              {devLink && (
                <div className="bg-primary-light border border-primary/20 rounded-lg p-3 mb-4 text-xs">
                  <p className="mb-1 font-medium uppercase text-[10px] text-primary">
                    No email service configured yet — dev link:
                  </p>
                  {/* Plain <a>, not <Link> -- devLink is a fully-qualified URL, and React
                      Router's <Link to> only understands internal SPA paths (see frontend/'s
                      own ForgotPassword.tsx for the same reasoning). */}
                  <a href={devLink} className="underline break-all text-primary">{devLink}</a>
                </div>
              )}
              <Link to="/login" className="text-sm text-primary font-medium">Back to sign in</Link>
            </>
          ) : (
            <form onSubmit={handleSubmit} noValidate>
              <h1 className="text-lg font-bold text-ink mb-2">Reset your password</h1>
              <p className="text-sm text-muted mb-6">Enter your email and we'll send you a reset link.</p>

              {error && <p className="text-sm text-danger bg-danger-bg rounded-lg px-3.5 py-2.5 mb-4">{error}</p>}

              <label htmlFor="forgot-email" className="block text-sm font-medium text-ink mb-1.5">Email</label>
              <input
                id="forgot-email"
                type="email"
                required
                autoFocus
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                onBlur={() => setTouched(true)}
                className="w-full bg-bg border border-border rounded-lg px-3.5 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-primary/30 mb-1"
              />
              <p className="text-[11px] mb-4 h-3.5">
                {touched && !emailValid && <span className="text-danger">Enter a valid email address.</span>}
              </p>

              <button
                type="submit"
                disabled={loading || !emailValid}
                className="w-full bg-primary hover:bg-primary-dark text-on-primary font-semibold rounded-lg py-2.5 text-sm disabled:opacity-50"
              >
                {loading ? 'Sending…' : 'Send reset link'}
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
