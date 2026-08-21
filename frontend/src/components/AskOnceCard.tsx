import { useEffect, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { HelpCircle, Check, ChevronLeft, ChevronRight } from 'lucide-react';
import { transactionsApi, categoriesApi } from '../api/endpoints';
import type { Transaction } from '../types';

const PAGE_SIZE = 10;

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
  const [page, setPage] = useState(0);
  const [error, setError] = useState<string | null>(null);

  function load() {
    setLoading(true);
    Promise.all([transactionsApi.needsReview(), categoriesApi.list()])
      .then(([txns, cats]) => {
        setItems(txns);
        setCategories(cats.map((c) => c.name));
      })
      // A failed fetch here just leaves items/categories at their default [] -- the widget
      // already renders nothing when items.length === 0, so this "no card" fallback for a
      // Dashboard nice-to-have is preferable to surfacing an error banner for it.
      .catch(() => {})
      .finally(() => setLoading(false));
  }
  useEffect(load, []);

  const totalPages = Math.max(1, Math.ceil(items.length / PAGE_SIZE));
  // Clamp rather than let `page` drift out of range once items shrink below the current page's
  // start (e.g. confirming every item on the last page, or a real-time removal below) -- without
  // this, the visible slice would silently go empty while the pager still shows a now-invalid
  // page number.
  useEffect(() => {
    if (page > totalPages - 1) setPage(Math.max(0, totalPages - 1));
  }, [totalPages, page]);
  const pageItems = items.slice(page * PAGE_SIZE, page * PAGE_SIZE + PAGE_SIZE);

  async function resolve(id: string) {
    const category = picks[id];
    if (!category) return;
    setResolving(id);
    setError(null);
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
      // Deliberately fire-and-forget: invalidateQueries()'s promise resolves once the background
      // refetch of active queries completes, which nothing here needs to wait on.
      void queryClient.invalidateQueries({ queryKey: ['transactions'] });
      void queryClient.invalidateQueries({ queryKey: ['recent-transactions'] });
      void queryClient.invalidateQueries({ queryKey: ['dashboard-summary'] });
      void queryClient.invalidateQueries({ queryKey: ['insights'] });
      void queryClient.invalidateQueries({ queryKey: ['budgets'] });
    } catch {
      // Bug fix: this had no catch at all -- a failed updateCategory() (network error, 500)
      // became an unhandled promise rejection with zero feedback to the user. The spinner still
      // cleared via `finally`, but the row silently stayed in the "needs review" list with no
      // explanation that the save didn't actually happen.
      setError("Couldn't save that category — please try again.");
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
      {error && <p className="text-xs text-danger mb-3">{error}</p>}
      <div className="space-y-3">
        {pageItems.map((t) => (
          <div key={t.id} className="flex items-center gap-3 flex-wrap sm:flex-nowrap">
            <div className="min-w-0 flex-1">
              <p className="text-sm font-medium text-ink truncate">{t.description || t.merchant}</p>
              <p className="text-[11px] text-muted">{t.date} · {fmt(t.amount)}</p>
            </div>
            <select
              value={picks[t.id] ?? ''}
              onChange={(e) => setPicks((p) => ({ ...p, [t.id]: e.target.value }))}
              className="bg-card text-ink border border-border rounded-lg px-2.5 py-1.5 text-xs flex-shrink-0"
            >
              <option value="" disabled>Choose category…</option>
              {categories.map((c) => <option key={c} value={c}>{c}</option>)}
            </select>
            <button
              onClick={() => resolve(t.id)}
              disabled={!picks[t.id] || resolving === t.id}
              className="bg-primary text-on-primary text-xs font-medium rounded-lg px-3 py-1.5 flex items-center gap-1 flex-shrink-0 disabled:opacity-40"
            >
              <Check size={13} /> {resolving === t.id ? 'Saving…' : 'Confirm'}
            </button>
          </div>
        ))}
      </div>
      {items.length > PAGE_SIZE && (
        <div className="flex items-center justify-between mt-4 pt-3 border-t border-border">
          <p className="text-[11px] text-muted">
            Showing {page * PAGE_SIZE + 1}-{Math.min(items.length, page * PAGE_SIZE + PAGE_SIZE)} of {items.length}
          </p>
          <div className="flex items-center gap-1.5">
            <button
              onClick={() => setPage((p) => Math.max(0, p - 1))}
              disabled={page === 0}
              className="w-7 h-7 rounded-lg border border-border flex items-center justify-center text-muted hover:text-ink hover:bg-bg disabled:opacity-30 disabled:hover:bg-transparent"
              aria-label="Previous page"
            >
              <ChevronLeft size={14} />
            </button>
            <span className="text-[11px] text-muted px-1">Page {page + 1} of {totalPages}</span>
            <button
              onClick={() => setPage((p) => Math.min(totalPages - 1, p + 1))}
              disabled={page >= totalPages - 1}
              className="w-7 h-7 rounded-lg border border-border flex items-center justify-center text-muted hover:text-ink hover:bg-bg disabled:opacity-30 disabled:hover:bg-transparent"
              aria-label="Next page"
            >
              <ChevronRight size={14} />
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
