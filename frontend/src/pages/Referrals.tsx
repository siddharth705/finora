import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Gift, Copy, Check, Users } from 'lucide-react';
import { referralsApi } from '../api/endpoints';
import { formatDate } from '../utils/date';
import { FinoraCard, EmptyState } from '../design-system';

function fmt(amount: number) {
  return '₹' + Math.round(amount).toLocaleString('en-IN');
}

function statusLabel(status: string) {
  switch (status) {
    case 'REWARDED': return { text: 'Rewarded', className: 'text-success bg-success-bg' };
    case 'SUBSCRIBED': return { text: 'Subscribed', className: 'text-primary bg-primary-light' };
    default: return { text: 'Registered', className: 'text-muted bg-bg' };
  }
}

/**
 * D-28 PR4-C. Refer & Earn (proposal §4) -- a user's own shareable code, their referrals, and
 * their wallet balance. The reward AMOUNT a referral eventually earns is set by an admin
 * (ReferralService.creditReward's own doc comment explains why), so this page shows whatever
 * REWARDED referrals actually earned -- it never predicts or advertises a number up front.
 */
export default function Referrals() {
  const [copied, setCopied] = useState(false);
  const { data: codeData, isLoading: codeLoading } = useQuery({
    queryKey: ['referral-my-code'],
    queryFn: () => referralsApi.myCode(),
  });
  const { data: mine, isLoading: mineLoading } = useQuery({
    queryKey: ['referrals-mine'],
    queryFn: () => referralsApi.mine(),
  });

  const shareLink = codeData ? `${window.location.origin}/register?ref=${codeData.code}` : '';

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

  const referrals = mine?.referrals ?? [];

  return (
    <div className="space-y-4">
      <div className="mb-2">
        <h1 className="text-xl font-bold text-ink">Refer & Earn</h1>
        <p className="text-sm text-muted">Share Finora with friends and earn rewards when they join.</p>
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
        {codeLoading ? (
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
        <p className="text-xs uppercase text-muted mb-1">Wallet balance</p>
        <p className="text-2xl font-bold text-ink">{mineLoading ? '—' : fmt(mine?.walletBalance ?? 0)}</p>
      </FinoraCard>

      {!mineLoading && referrals.length === 0 ? (
        <FinoraCard padding="lg">
          <EmptyState
            icon={Users}
            iconBg="bg-blue-100"
            iconColor="text-blue-600"
            title="No referrals yet"
            desc="Share your link above — when a friend signs up with it, they'll show up here."
          />
        </FinoraCard>
      ) : (
        <div className="bg-card rounded-xl2 shadow-card border border-border overflow-hidden">
          <div className="divide-y divide-border">
            {referrals.map((r) => {
              const status = statusLabel(r.status);
              return (
                <div key={r.referralId} className="px-5 py-3.5 flex items-center justify-between gap-4 flex-wrap">
                  <div className="min-w-0">
                    <p className="text-sm font-medium text-ink truncate">{r.referredUserFullName ?? 'A new user'}</p>
                    <p className="text-xs text-muted">
                      Joined {formatDate(r.createdAt)}{r.reward != null ? ` · Earned ${fmt(r.reward)}` : ''}
                    </p>
                  </div>
                  <span className={`text-[10px] uppercase font-semibold rounded px-2 py-1 ${status.className}`}>
                    {status.text}
                  </span>
                </div>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
}
