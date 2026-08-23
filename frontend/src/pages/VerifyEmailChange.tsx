import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Sparkles } from 'lucide-react';
import { emailChangeApi } from '../api/endpoints';

/**
 * Phase 4 (change email). Lands from the link ChangeEmailModal/EmailChangeService emails to the
 * NEW address. Same "read the params, call the endpoint(s) on mount, three states" shape as
 * VerifyEmail.tsx (registration's equivalent page), with two differences: this one needs BOTH
 * sessionId and token from the link (see EmailChangeService.start's own doc comment on why the
 * link carries both), and it chains verify() straight into complete() -- there's no reason to make
 * a second round trip from a still-open browser tab when landing here already proves the whole
 * point of both calls.
 *
 * Reached inside the authenticated app shell (ProtectedRoute), unlike VerifyEmail.tsx: both
 * backend endpoints require the caller to already be logged in as the account making the change
 * (see EmailChangeService's own doc comment on why this stays an authenticated flow, not a public
 * one like password reset).
 */
export default function VerifyEmailChange() {
  const [searchParams] = useSearchParams();
  const sessionId = searchParams.get('sessionId');
  const token = searchParams.get('token');
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [newEmail, setNewEmail] = useState<string | null>(null);

  useEffect(() => {
    if (!sessionId || !token) {
      setError('This link is missing information — please use the link from the email exactly as sent.');
      setLoading(false);
      return;
    }
    emailChangeApi.verify(sessionId, token)
      .then(() => emailChangeApi.complete(sessionId))
      .then((res) => setNewEmail(res.email))
      .catch((err: any) => setError(err.response?.data?.message ?? 'This confirmation link is invalid or has expired.'))
      .finally(() => setLoading(false));
  }, [sessionId, token]);

  return (
    <div className="min-h-screen flex items-center justify-center bg-bg">
      <div className="bg-card rounded-xl2 p-8 w-full max-w-sm shadow-soft border border-border text-center">
        <div className="flex items-center justify-center gap-2 mb-6">
          <span className="w-7 h-7 rounded-lg bg-gradient-to-br from-primary to-primary-dark flex items-center justify-center">
            <Sparkles size={14} className="text-on-primary" strokeWidth={2.5} />
          </span>
          <span className="font-extrabold tracking-wide text-ink">FINORA</span>
        </div>

        {loading ? (
          <p className="text-sm text-muted">Confirming your new email…</p>
        ) : newEmail ? (
          <>
            <h1 className="text-2xl font-bold mb-2 text-ink">Email updated</h1>
            <p className="text-sm text-muted mb-6">
              Your account email is now {newEmail}. Sign in with this address from now on.
            </p>
            <Link
              to="/app/profile"
              className="inline-block w-full bg-primary hover:bg-primary-dark text-on-primary rounded-lg py-2.5 text-sm font-semibold"
            >
              Back to Profile
            </Link>
          </>
        ) : (
          <>
            <h1 className="text-2xl font-bold mb-2 text-ink">Confirmation failed</h1>
            <p className="text-sm text-danger mb-6">{error}</p>
            <Link to="/app/profile" className="text-primary font-medium text-sm">Back to Profile</Link>
          </>
        )}
      </div>
    </div>
  );
}
