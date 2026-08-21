import { useQuery } from '@tanstack/react-query';
import { Receipt } from 'lucide-react';
import { billingApi } from '../api/endpoints';
import { formatDate } from '../utils/date';
import { FinoraCard, EmptyState } from '../design-system';

function fmt(amount: number, currency: string) {
  const symbol = currency === 'INR' ? '₹' : currency + ' ';
  return symbol + Math.round(amount).toLocaleString('en-IN');
}

function statusLabel(status: string) {
  switch (status) {
    case 'SUCCESS': return { text: 'Paid', className: 'text-success bg-success-bg' };
    case 'REFUNDED': return { text: 'Refunded', className: 'text-muted bg-bg' };
    case 'FAILED': return { text: 'Failed', className: 'text-danger bg-danger-bg' };
    default: return { text: 'Pending', className: 'text-warning bg-warning-bg' };
  }
}

/**
 * D-28 PR4-B. Billing history scaffolding (proposal §3.4) -- a read view over the user's own
 * payments. Empty for every user today: no payment gateway is wired up yet (§10), so the empty
 * state below is the only state this page can actually be in right now, not an unfinished branch.
 * The populated branch is written and tested against real data shapes (BillingHistoryControllerIT)
 * so a future gateway integration only has to start inserting rows, not build a UI for them.
 */
export default function BillingHistory() {
  const { data: entries, isLoading } = useQuery({
    queryKey: ['billing-history'],
    queryFn: () => billingApi.history(),
  });

  if (isLoading) return <p className="text-muted">Loading…</p>;

  const payments = entries ?? [];

  return (
    <div className="space-y-4">
      <div className="mb-2">
        <h1 className="text-xl font-bold text-ink">Billing History</h1>
        <p className="text-sm text-muted">Your Finora payment records.</p>
      </div>

      {payments.length === 0 ? (
        <FinoraCard padding="lg">
          <EmptyState
            icon={Receipt}
            iconBg="bg-blue-100"
            iconColor="text-blue-600"
            title="No billing history yet"
            desc="Finora is currently free to use — payment records will appear here once billing is available."
          />
        </FinoraCard>
      ) : (
        <div className="bg-card rounded-xl2 shadow-card border border-border overflow-hidden">
          <div className="divide-y divide-border">
            {payments.map((p) => {
              const status = statusLabel(p.status);
              return (
                <div key={p.id} className="px-5 py-3.5 flex items-center justify-between gap-4 flex-wrap">
                  <div className="min-w-0">
                    <p className="text-sm font-medium text-ink">{fmt(p.amount, p.currency)}</p>
                    <p className="text-xs text-muted">
                      {formatDate(p.createdAt)}{p.provider ? ` · ${p.provider}` : ''}
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
