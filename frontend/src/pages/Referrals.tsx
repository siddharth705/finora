import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Gift, Copy, Check, Users } from 'lucide-react';
import { referralsApi } from '../api/endpoints';
import { FinoraCard } from '../design-system';

/**
 * Refer & Earn MVP -- a user's own shareable code, a copy button, and how many people they've
 * referred. Nothing else: no rewards, no wallet, no per-referral list (see ReferralService's own
 * doc comment for the scope this replaced). The only thing being validated right now is whether
 * people are willing to refer others at all.
 */
export default function Referrals() {
  const [copied, setCopied] = useState(false);
  const { data, isLoading } = useQuery({
    queryKey: ['referrals-mine'],
    queryFn: () => referralsApi.mine(),
  });

  const shareLink = data ? `${window.location.origin}/register?ref=${data.code}` : '';

  async function handleCopy() {
    try {
      await navigator.clipboard.writeText(shareLink);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // Clipboard API unavailable (older browser, insecure context) -- the link is already
      // visible in the field below for a manual select-and-copy, so there's nothing more useful
      // to do than leave it there.
    }
  }

  return (
    <div className="space-y-4">
      <div className="mb-2">
        <h1 className="text-xl font-bold text-ink">Refer & Earn</h1>
        <p className="text-sm text-muted">Share Fynora with friends and see who joins.</p>
      </div>

      <FinoraCard padding="lg">
        <div className="flex items-center gap-2.5 mb-3">
          <div className="w-9 h-9 rounded-full bg-primary-light flex items-center justify-center">
            <Gift size={16} className="text-primary" />
          </div>
          <div>
            <p className="text-sm font-semibold text-ink">Your referral link</p>
            <p className="text-xs text-muted">Anyone who signs up with this link is credited to you.</p>
          </div>
        </div>
        {isLoading ? (
          <p className="text-sm text-muted">Loading…</p>
        ) : (
          <div className="flex items-center gap-2">
            <input
              readOnly
              value={shareLink}
              className="flex-1 min-w-0 border border-border rounded-lg px-3 py-2 text-sm bg-bg text-ink"
              onFocus={(e) => e.target.select()}
            />
            <button
              type="button"
              onClick={handleCopy}
              className="flex items-center gap-1.5 bg-primary text-white text-xs font-semibold rounded-lg px-3 py-2 flex-shrink-0"
            >
              {copied ? <Check size={14} /> : <Copy size={14} />}
              {copied ? 'Copied' : 'Copy'}
            </button>
          </div>
        )}
      </FinoraCard>

      <FinoraCard padding="lg">
        <div className="flex items-center gap-2.5">
          <div className="w-9 h-9 rounded-full bg-blue-100 flex items-center justify-center">
            <Users size={16} className="text-blue-600" />
          </div>
          <div>
            <p className="text-xs uppercase text-muted">Referrals</p>
            <p className="text-2xl font-bold text-ink">{isLoading ? '—' : (data?.referralCount ?? 0)}</p>
          </div>
        </div>
      </FinoraCard>
    </div>
  );
}
