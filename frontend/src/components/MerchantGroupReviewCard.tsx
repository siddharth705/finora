import { useEffect, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion';
import { Users, Check, ChevronDown } from 'lucide-react';
import { transactionsApi } from '../api/endpoints';
import { CategoryCombobox } from './CategoryCombobox';
import { CategoryCreateEditPanel } from './CategoryCreateEditPanel';
import { ReviewCardSkeleton } from './ReviewCardSkeleton';
import type { MerchantGroup } from '../types';

function fmt(n: number) {
  // Negative amounts must render as "-₹500", not "₹-500" -- same fix Ledger.tsx's own fmt
  // already applies; duplicated rather than imported since neither app has a shared
  // lib/format.ts for the web frontend (unlike mobile) -- every page/component that needs this
  // keeps its own copy.
  return (n < 0 ? '-₹' : '₹') + Math.round(Math.abs(n)).toLocaleString('en-IN');
}

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
  const prefersReducedMotion = useReducedMotion();
  const [groups, setGroups] = useState<MerchantGroup[]>([]);
  const [picks, setPicks] = useState<Record<string, string>>({});
  const [creatingFor, setCreatingFor] = useState<string | null>(null);
  const [pendingText, setPendingText] = useState<Record<string, string>>({});
  const [applying, setApplying] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // Reported directly: applying a category to "12 transactions" sight-unseen asks for trust the
  // card gave no way to check. Independent per group (a Set, not one shared id) -- reviewing one
  // merchant's rows is a reason to look at another's too, not a reason to lose the first.
  const [expanded, setExpanded] = useState<Set<string>>(new Set());

  function load() {
    setLoading(true);
    transactionsApi.groupsNeedsReview()
      .then(setGroups)
      .catch(() => {})
      .finally(() => setLoading(false));
  }
  useEffect(load, []);

  function toggleExpanded(merchantId: string) {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(merchantId)) next.delete(merchantId);
      else next.add(merchantId);
      return next;
    });
  }

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

  if (!loading && groups.length === 0) return null;

  return (
    <div className="bg-card rounded-xl2 p-5 shadow-card border border-border mb-6">
      <div className="flex items-center gap-2 mb-1">
        <Users size={17} className="text-primary" />
        <h2 className="font-semibold text-ink text-sm">Categorize a whole merchant at once</h2>
      </div>
      <p className="text-xs text-muted mb-4">
        These merchants have multiple transactions needing a category — apply one to all of them.
      </p>
      {loading ? (
        <ReviewCardSkeleton />
      ) : (
        <>
          {error && <p className="text-xs text-danger mb-3">{error}</p>}
          <div className="space-y-3">
            {groups.map((g) => {
              const isExpanded = expanded.has(g.merchantId);
              return (
                <div key={g.merchantId} className="border border-border rounded-lg p-3">
                  <div className={`flex gap-3 flex-wrap sm:flex-nowrap ${creatingFor === g.merchantId ? 'items-start' : 'items-center'}`}>
                    <button
                      type="button"
                      onClick={() => toggleExpanded(g.merchantId)}
                      aria-expanded={isExpanded}
                      aria-label={`${isExpanded ? 'Hide' : 'Show'} the ${g.transactionIds.length} transactions for ${g.merchantName}`}
                      className="min-w-0 flex-1 flex items-center gap-2 text-left"
                    >
                      <ChevronDown
                        size={14}
                        className={`text-muted flex-shrink-0 transition-transform duration-200 ${isExpanded ? 'rotate-180' : ''}`}
                        aria-hidden="true"
                      />
                      <span className="min-w-0">
                        <p className="text-sm font-medium text-ink truncate">{g.merchantName}</p>
                        <p className="text-[11px] text-muted">{g.transactionIds.length} transactions</p>
                      </span>
                    </button>
                    <div className={`flex-shrink-0 ${creatingFor === g.merchantId ? 'w-64' : 'w-40'}`}>
                      {creatingFor === g.merchantId ? (
                        <CategoryCreateEditPanel
                          mode="create"
                          initialName={pendingText[g.merchantId] ?? ''}
                          onSaved={(c) => { setPicks((p) => ({ ...p, [g.merchantId]: c.name })); setCreatingFor(null); }}
                          onCancel={() => setCreatingFor(null)}
                        />
                      ) : (
                        <CategoryCombobox
                          value={picks[g.merchantId] ?? ''}
                          onChange={(name) => setPicks((p) => ({ ...p, [g.merchantId]: name }))}
                          onCreateNew={(text) => { setPendingText((p) => ({ ...p, [g.merchantId]: text })); setCreatingFor(g.merchantId); }}
                        />
                      )}
                    </div>
                    <motion.button
                      whileTap={{ scale: 0.96 }}
                      onClick={() => apply(g)}
                      disabled={!picks[g.merchantId] || applying === g.merchantId}
                      className="bg-primary text-on-primary text-xs font-medium rounded-lg px-3 py-1.5 flex items-center gap-1 flex-shrink-0 disabled:opacity-40"
                    >
                      <Check size={13} />
                      {applying === g.merchantId ? 'Applying…' : `Apply to ${g.transactionIds.length} transactions`}
                    </motion.button>
                  </div>
                  <AnimatePresence initial={false}>
                    {isExpanded && (
                      <motion.ul
                        initial={prefersReducedMotion ? undefined : { opacity: 0, height: 0 }}
                        animate={{ opacity: 1, height: 'auto' }}
                        exit={prefersReducedMotion ? undefined : { opacity: 0, height: 0 }}
                        transition={{ duration: 0.18, ease: 'easeOut' }}
                        className="mt-3 pt-3 border-t border-border space-y-1.5 overflow-hidden"
                      >
                        {g.transactions.map((t) => (
                          <li key={t.id} className="flex items-center gap-3 text-xs">
                            <span className="text-muted flex-shrink-0 w-20">{t.date}</span>
                            <span className="text-ink truncate flex-1 min-w-0">{t.description}</span>
                            <span className={`flex-shrink-0 ${t.type === 'INCOME' ? 'text-success' : 'text-danger'}`}>
                              {t.type === 'INCOME' ? '+' : '-'}{fmt(t.amount)}
                            </span>
                          </li>
                        ))}
                      </motion.ul>
                    )}
                  </AnimatePresence>
                </div>
              );
            })}
          </div>
        </>
      )}
    </div>
  );
}
