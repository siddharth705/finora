import { useEffect, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { HelpCircle, Check } from 'lucide-react';
import { transactionsApi, categoriesApi } from '../api/endpoints';
import type { Transaction } from '../types';

function fmt(n: number) {
  // Negative amounts (e.g. a month where spend exceeded income) must render as "-₹500",
  // not "₹-500" -- string concatenation put the currency symbol before the sign.
  return (n < 0 ? '-₹' : '₹') + Math.round(Math.abs(n)).toLocaleString('en-IN');
}

/**
 * "Ask Once, Learn Forever": transactions the categorization engine had no confident guess
 * for. Resolving one here calls the same PATCH .../category endpoint used everywhere else in
 * the app, which both applies the category and teaches the merchant map — so the same
 * merchant never shows up here again.
 */
export function AskOnceCard() {
  const queryClient = useQueryClient();
  const [items, setItems] = useState<Transaction[]>([]);
  const [categories, setCategories] = useState<string[]>([]);
  const [picks, setPicks] = useState<Record<string, string>>({});
  const [resolving, setResolving] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  function load() {
    setLoading(true);
    Promise.all([transactionsApi.needsReview(), categoriesApi.list()])
      .then(([txns, cats]) => {
        setItems(txns);
        setCategories(cats.map((c) => c.name));
      })
      .finally(() => setLoading(false));
  }
  useEffect(load, []);

  async function resolve(id: string) {
    const category = picks[id];
    if (!category) return;
    setResolving(id);
    try {
      await transactionsApi.updateCategory(id, category);
      setItems((prev) => prev.filter((t) => t.id !== id));
      // This card now lives on the Transactions page (Ledger.tsx), directly above the table
      // showing the very row just re-categorized — without this, the table keeps its stale
      // TanStack Query cache (old category, "needs review" badge still on) until some unrelated
      // refetch happens. Also covers the Dashboard's recent-transactions list and
      // spend-by-category totals, both of which shift when a category changes. 'budgets' is
      // needed too — moving a transaction into/out of a category directly changes that
      // category's spentThisMonth, which the Dashboard's Budget Progress card reads. Deliberately
      // not invalidating 'report'/'report-months': a category-only edit doesn't change a
      // transaction's income/expense total, which is all that chart is built from.
      queryClient.invalidateQueries({ queryKey: ['transactions'] });
      queryClient.invalidateQueries({ queryKey: ['recent-transactions'] });
      queryClient.invalidateQueries({ queryKey: ['dashboard-summary'] });
      queryClient.invalidateQueries({ queryKey: ['insights'] });
      queryClient.invalidateQueries({ queryKey: ['budgets'] });
    } finally {
      setResolving(null);
    }
  }

  if (loading || items.length === 0) return null;

  return (
    <div className="bg-card rounded-xl2 p-5 shadow-card border border-border mb-6">
      <div className="flex items-center gap-2 mb-1">
        <HelpCircle size={17} className="text-primary" />
        <h2 className="font-semibold text-ink text-sm">A few transactions need your input</h2>
      </div>
      <p className="text-xs text-muted mb-4">
        Pick a category once — Finora will remember it for every future transaction from the same merchant.
      </p>
      <div className="space-y-3">
        {items.map((t) => (
          <div key={t.id} className="flex items-center gap-3 flex-wrap sm:flex-nowrap">
            <div className="min-w-0 flex-1">
              <p className="text-sm font-medium text-ink truncate">{t.description || t.merchant}</p>
              <p className="text-[11px] text-muted">{t.date} · {fmt(t.amount)}</p>
            </div>
            <select
              value={picks[t.id] ?? ''}
              onChange={(e) => setPicks((p) => ({ ...p, [t.id]: e.target.value }))}
              className="border border-border rounded-lg px-2.5 py-1.5 text-xs flex-shrink-0"
            >
              <option value="" disabled>Choose category…</option>
              {categories.map((c) => <option key={c} value={c}>{c}</option>)}
            </select>
            <button
              onClick={() => resolve(t.id)}
              disabled={!picks[t.id] || resolving === t.id}
              className="bg-primary text-white text-xs font-medium rounded-lg px-3 py-1.5 flex items-center gap-1 flex-shrink-0 disabled:opacity-40"
            >
              <Check size={13} /> {resolving === t.id ? 'Saving…' : 'Confirm'}
            </button>
          </div>
        ))}
      </div>
    </div>
  );
}
