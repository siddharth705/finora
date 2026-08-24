import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Sparkles } from 'lucide-react';
import { authApi } from '../api/endpoints';

/**
 * D-23. Lands from the link in the verification email (register(), or a fresh one loginWithGoogle
 * sends when it finds a matching but not-yet-verified account -- see AuthContext.loginWithGoogle's
 * own error message). Same "read the token, call the endpoint on mount, three states" shape as
 * ResetPassword.tsx's own token-landing page, simplified: no second factor, no form -- just
 * confirming a fact.
 */
export default function VerifyEmail() {
  const [searchParams] = useSearchParams();
  const token = searchParams.get('token');
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [done, setDone] = useState(false);

  useEffect(() => {
    if (!token) {
      setError('No verification token found in the link.');
      setLoading(false);
      return;
    }
    authApi.verifyEmail(token)
      .then(() => setDone(true))
      .catch((err: any) => setError(err.response?.data?.message ?? 'This verification link is invalid or has expired.'))
      .finally(() => setLoading(false));
  }, [token]);

  return (
    <div className="min-h-screen flex items-center justify-center bg-bg">
      <div className="bg-card rounded-xl2 p-8 w-full max-w-sm shadow-soft border border-border text-center">
        <div className="flex items-center justify-center gap-2 mb-6">
          <span className="w-7 h-7 rounded-lg bg-gradient-to-br from-primary to-primary-dark flex items-center justify-center">
            <Sparkles size={14} className="text-on-primary" strokeWidth={2.5} />
          </span>
          <span className="font-extrabold tracking-wide text-ink">FYNORA</span>
        </div>

        {loading ? (
          <p className="text-sm text-muted">Verifying your email…</p>
        ) : done ? (
          <>
            <h1 className="text-2xl font-bold mb-2 text-ink">Email verified</h1>
            <p className="text-sm text-muted mb-6">
              You're all set. If you were signing in with Google, you can go back and try again.
            </p>
            <Link
              to="/auth"
              className="inline-block w-full bg-primary hover:bg-primary-dark text-on-primary rounded-lg py-2.5 text-sm font-semibold"
            >
              Continue to sign in
            </Link>
          </>
        ) : (
          <>
            <h1 className="text-2xl font-bold mb-2 text-ink">Verification failed</h1>
            <p className="text-sm text-danger mb-6">{error}</p>
            <Link to="/auth" className="text-primary font-medium text-sm">Back to sign in</Link>
          </>
        )}
      </div>
    </div>
  );
}
