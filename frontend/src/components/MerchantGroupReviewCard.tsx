import { useEffect, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Users, Check } from 'lucide-react';
import { transactionsApi, categoriesApi } from '../api/endpoints';
import type { MerchantGroup } from '../types';

/**
 * "5 Swiggy transactions found" — bulk-apply a category to every needs-review transaction sharing
 * a merchant, in one action. Same load/select/confirm shape as AskOnceCard, and calls the same
 * category-write path (bulkRecategorize, which itself queues the identical merchant-learning event
 * updateCategory does) — the two cards split the needs-review backlog by group size, they don't
 * duplicate each other's job. Groups of one stay in AskOnceCard; this only ever shows groups of 2+
 * (TransactionGroupingService.groupNeedsReviewByMerchant already filters that server-side).
 */
export function MerchantGroupReviewCard() {
  const queryClient = useQueryClient();
  const [groups, setGroups] = useState<MerchantGroup[]>([]);
  const [categories, setCategories] = useState<string[]>([]);
  const [picks, setPicks] = useState<Record<string, string>>({});
  const [applying, setApplying] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  function load() {
    setLoading(true);
    Promise.all([transactionsApi.groupsNeedsReview(), categoriesApi.list()])
      .then(([g, cats]) => {
        setGroups(g);
        setCategories(cats.map((c) => c.name));
      })
      .catch(() => {})
      .finally(() => setLoading(false));
  }
  useEffect(load, []);

  async function apply(group: MerchantGroup) {
    const category = picks[group.merchantId];
    if (!category) return;
    setApplying(group.merchantId);
    setError(null);
    try {
      await transactionsApi.bulkRecategorize(group.transactionIds, category);
      setGroups((prev) => prev.filter((g) => g.merchantId !== group.merchantId));
      void queryClient.invalidateQueries({ queryKey: ['transactions'] });
      void queryClient.invalidateQueries({ queryKey: ['recent-transactions'] });
      void queryClient.invalidateQueries({ queryKey: ['dashboard-summary'] });
      void queryClient.invalidateQueries({ queryKey: ['insights'] });
      void queryClient.invalidateQueries({ queryKey: ['budgets'] });
    } catch {
      setError("Couldn't apply that category — please try again.");
    } finally {
      setApplying(null);
    }
  }

  if (loading || groups.length === 0) return null;

  return (
    <div className="bg-card rounded-xl2 p-5 shadow-card border border-border mb-6">
      <div className="flex items-center gap-2 mb-1">
        <Users size={17} className="text-primary" />
        <h2 className="font-semibold text-ink text-sm">Categorize a whole merchant at once</h2>
      </div>
      <p className="text-xs text-muted mb-4">
        These merchants have multiple transactions needing a category — apply one to all of them.
      </p>
      {error && <p className="text-xs text-danger mb-3">{error}</p>}
      <div className="space-y-3">
        {groups.map((g) => (
          <div key={g.merchantId} className="flex items-center gap-3 flex-wrap sm:flex-nowrap">
            <div className="min-w-0 flex-1">
              <p className="text-sm font-medium text-ink truncate">{g.merchantName}</p>
              <p className="text-[11px] text-muted">{g.transactionIds.length} transactions</p>
            </div>
            <select
              value={picks[g.merchantId] ?? ''}
              onChange={(e) => setPicks((p) => ({ ...p, [g.merchantId]: e.target.value }))}
              className="bg-card text-ink border border-border rounded-lg px-2.5 py-1.5 text-xs flex-shrink-0"
            >
              <option value="" disabled>Choose category…</option>
              {categories.map((c) => <option key={c} value={c}>{c}</option>)}
            </select>
            <button
              onClick={() => apply(g)}
              disabled={!picks[g.merchantId] || applying === g.merchantId}
              className="bg-primary text-on-primary text-xs font-medium rounded-lg px-3 py-1.5 flex items-center gap-1 flex-shrink-0 disabled:opacity-40"
            >
              <Check size={13} />
              {applying === g.merchantId ? 'Applying…' : `Apply to ${g.transactionIds.length} transactions`}
            </button>
          </div>
        ))}
      </div>
    </div>
  );
}
