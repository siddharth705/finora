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
 * Bug fix (self-review): verify() requires the session to be exactly STARTED server-side, so
 * revisiting this page (refresh, a double-click, opening the link a second time) after an
 * earlier visit already advanced it past STARTED fails verify() with a generic "already
 * completed" message -- even when the email change genuinely succeeded the first time. complete()
 * is idempotent and authoritative about the real outcome either way, so a verify() failure falls
 * back to it rather than giving up immediately: if the session really was already fully done,
 * complete() returns the same success response it did the first time; if verify() failed for a
 * real reason (bad/expired token), complete() fails too (the session never reached EMAIL_VERIFIED),
 * and verify()'s own message is shown -- it's normally the more specific one.
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

    async function run(id: string, t: string) {
      try {
        await emailChangeApi.verify(id, t);
      } catch (verifyErr: any) {
        try {
          const res = await emailChangeApi.complete(id);
          setNewEmail(res.email);
        } catch {
          setError(verifyErr.response?.data?.message ?? 'This confirmation link is invalid or has expired.');
        }
        return;
      }
      try {
        const res = await emailChangeApi.complete(id);
        setNewEmail(res.email);
      } catch (completeErr: any) {
        setError(completeErr.response?.data?.message ?? 'This confirmation link is invalid or has expired.');
      }
    }

    void run(sessionId, token).finally(() => setLoading(false));
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

        {/* Mobile has no way to intercept this page's own https:// URL directly (no hosted
            apple-app-site-association/assetlinks.json for a true universal/app link -- see
            mobile/src/navigation/RootNavigator.tsx's own doc comment on that), so anyone reading
            the confirmation email on their phone needs an explicit way in. finora:// is the custom
            scheme RootNavigator's `linking` config registers for exactly this sessionId/token
            path. Shown whenever both are present, independent of this page's own verify/complete
            state -- someone reading this email on a phone likely wants to jump straight to the
            app rather than wait for (or even trigger) this browser tab's own attempt. */}
        {sessionId && token && (
          <a
            href={`finora://email-change-verify?sessionId=${encodeURIComponent(sessionId)}&token=${encodeURIComponent(token)}`}
            className="block text-xs text-primary font-medium mb-4"
          >
            Open in the Finora app
          </a>
        )}

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
