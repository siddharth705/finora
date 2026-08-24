import { useState, type FormEvent } from 'react';
import { User } from 'lucide-react';
import { authApi } from '../../api/endpoints';

// Matches RegisterStep's own EMAIL_PATTERN -- used here only to decide which of Register's two
// fields (email vs mobile number) to prefill when nextAction is CONTINUE, not as a submission
// gate (the backend is the one source of truth for what counts as a valid identifier).
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

interface IdentifyStepProps {
  onExists: (identifier: string) => void;
  onContinue: (identifier: string, prefill: { email?: string; phoneNumber?: string }) => void;
}

export function IdentifyStep({ onExists, onContinue }: IdentifyStepProps) {
  const [identifier, setIdentifier] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const identifierValid = identifier.trim().length > 0;

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (!identifierValid) { setError('Enter your email or mobile number.'); return; }
    setLoading(true);
    try {
      const trimmed = identifier.trim();
      const { nextAction } = await authApi.identify(trimmed);
      if (nextAction === 'CONTINUE') {
        const isEmail = EMAIL_PATTERN.test(trimmed);
        onContinue(trimmed, isEmail ? { email: trimmed } : { phoneNumber: trimmed });
      } else {
        onExists(trimmed);
      }
    } catch (err: any) {
      setError(err.response?.data?.message ?? 'Something went wrong. Please try again.');
    } finally {
      setLoading(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} noValidate>
      <h2 className="text-2xl font-extrabold text-ink mb-1">Sign in or create an account</h2>
      <p className="text-sm text-muted mb-6">Enter your email or mobile number to continue</p>

      {error && <p className="text-danger text-sm mb-4">{error}</p>}

      <label htmlFor="auth-entry-identifier" className="block text-xs font-medium text-muted mb-1">Email or mobile number</label>
      <div className="relative mb-6">
        <User size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-muted" />
        <input
          id="auth-entry-identifier"
          type="text"
          required
          autoComplete="username"
          value={identifier}
          onChange={(e) => setIdentifier(e.target.value)}
          placeholder="you@example.com or +91XXXXXXXXXX"
          className="w-full border border-border rounded-lg pl-9 pr-3 py-2.5 text-sm bg-white text-gray-900 focus:outline-none focus:ring-2 focus:ring-primary/30"
        />
      </div>

      <button
        type="submit"
        disabled={loading}
        className="w-full bg-primary hover:bg-primary-dark text-on-primary rounded-lg py-2.5 text-sm font-semibold disabled:opacity-50"
      >
        {loading ? 'Continuing…' : 'Continue'}
      </button>
    </form>
  );
}
