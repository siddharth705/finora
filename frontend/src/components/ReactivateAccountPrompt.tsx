import { useState } from 'react';
import { ShieldCheck } from 'lucide-react';
import { useAuth } from '../context/AuthContext';

/**
 * Shown in place of the login form once AuthService.login() has already proved the password is
 * correct and reported AUTH_ACCOUNT_DEACTIVATED -- see Login.tsx's catch block. `token` is the
 * reactivation token that error's `details` map carried; a single confirm click is all that's
 * left, since the password check already happened.
 */
export function ReactivateAccountPrompt({ token, onCancel, onReactivated }: {
  token: string;
  onCancel: () => void;
  onReactivated: (phoneVerified: boolean) => void;
}) {
  const { reactivate } = useAuth();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleReactivate() {
    setLoading(true);
    setError(null);
    try {
      const phoneVerified = await reactivate(token);
      onReactivated(phoneVerified);
    } catch (err: any) {
      // Most likely cause: the link expired (15 min) or was already used in another tab --
      // either way, the fix is the same one every other stale-token failure in this app uses:
      // go back and try again.
      setError(err.response?.data?.message ?? 'Could not reactivate your account. Please try signing in again.');
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="bg-card rounded-xl2 p-8 w-full shadow-soft border border-border">
      <h2 className="text-2xl font-bold text-ink mb-1">Welcome back</h2>
      <p className="text-sm text-muted mb-6">
        Your Finora account is deactivated. Sign in again to reactivate it — your data was
        retained and nothing was lost.
      </p>

      {error && <p className="text-danger text-sm mb-4">{error}</p>}

      <button
        type="button"
        onClick={handleReactivate}
        disabled={loading}
        className="w-full bg-primary hover:bg-primary-dark text-white rounded-lg py-2.5 text-sm font-semibold disabled:opacity-50"
      >
        {loading ? 'Reactivating…' : 'Reactivate my account'}
      </button>
      <button
        type="button"
        onClick={onCancel}
        disabled={loading}
        className="w-full mt-2 text-xs text-muted hover:text-ink py-1.5 disabled:opacity-50"
      >
        Not you? Go back
      </button>

      <div className="flex items-start gap-2.5 bg-primary-light rounded-lg p-3 mt-6">
        <ShieldCheck size={16} className="text-primary flex-shrink-0 mt-0.5" />
        <p className="text-xs text-ink">This link is valid for 15 minutes and can only be used once.</p>
      </div>
    </div>
  );
}
