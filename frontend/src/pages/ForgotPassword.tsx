import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { Sparkles } from 'lucide-react';
import { authApi } from '../api/endpoints';

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

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
    <div className="min-h-screen flex items-center justify-center bg-bg">
      <div className="bg-card rounded-xl2 p-8 w-full max-w-sm shadow-soft border border-border">
        <div className="flex items-center gap-2 mb-6">
          <span className="w-7 h-7 rounded-lg bg-gradient-to-br from-primary to-primary-dark flex items-center justify-center">
            <Sparkles size={14} className="text-on-primary" strokeWidth={2.5} />
          </span>
          <span className="font-display font-extrabold tracking-wide text-ink">FYNORA</span>
        </div>

        {submitted ? (
          <>
            <h1 className="text-2xl font-bold mb-3 text-ink">Check your email</h1>
            <p className="text-sm text-muted leading-relaxed mb-4">
              If an account exists for <strong>{email.trim()}</strong>, a reset link has been issued.
            </p>
            {devLink && (
              <div className="bg-primary-light border border-primary/20 rounded-lg p-3 mb-4 text-xs">
                <p className="mb-1 font-medium uppercase text-[10px] text-primary">No email service configured yet — dev link:</p>
                {/* Plain <a>, not <Link> — devLink is a fully-qualified URL (http://host/reset-password?token=...),
                    and React Router's <Link to> only understands internal SPA paths. Passing an absolute URL to
                    <Link to> resolves it relative to the current route instead of navigating to it, breaking this
                    link every time. */}
                <a href={devLink} className="underline break-all text-primary">{devLink}</a>
              </div>
            )}
            <Link to="/auth" className="text-sm text-primary font-medium">Back to sign in</Link>
          </>
        ) : (
          <form onSubmit={handleSubmit} noValidate>
            <h1 className="text-2xl font-bold mb-2 text-ink">Reset your password</h1>
            <p className="text-sm text-muted mb-6">Enter your email and we'll send you a reset link.</p>

            {error && <p className="text-danger text-sm mb-4">{error}</p>}

            <label htmlFor="forgot-password-email" className="block text-xs font-medium text-muted mb-1">Email</label>
            <input
              id="forgot-password-email"
              type="email"
              required
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              onBlur={() => setTouched(true)}
              className="bg-white text-gray-900 w-full border border-border rounded-lg px-3 py-2.5 mb-1 text-sm focus:outline-none focus:ring-2 focus:ring-primary/30"
            />
            <p className="text-[11px] mb-4 h-3.5">
              {touched && !emailValid && <span className="text-danger">Enter a valid email address.</span>}
            </p>

            <button
              type="submit"
              disabled={loading || !emailValid}
              className="w-full bg-primary hover:bg-primary-dark text-on-primary rounded-lg py-2.5 text-sm font-semibold disabled:opacity-50"
            >
              {loading ? 'Sending…' : 'Send reset link'}
            </button>

            <p className="text-sm mt-4 text-center">
              <Link to="/auth" className="text-primary font-medium">Back to sign in</Link>
            </p>
          </form>
        )}
      </div>
    </div>
  );
}
