import { useEffect, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion';
import { Wallet, Check, ChevronDown } from 'lucide-react';
import { transactionsApi } from '../api/endpoints';
import { CategoryCombobox } from './CategoryCombobox';
import { CategoryCreateEditPanel } from './CategoryCreateEditPanel';
import { ReviewCardSkeleton } from './ReviewCardSkeleton';
import type { CounterpartyGroup } from '../types';

function fmt(n: number) {
  // Same fix as Ledger.tsx / MerchantGroupReviewCard's own fmt: "-₹500", not "₹-500".
  return (n < 0 ? '-₹' : '₹') + Math.round(Math.abs(n)).toLocaleString('en-IN');
}

/**
 * "₹40,000 across 3 transactions with this person" — bulk-apply a category to every needs-review
 * transaction sharing a counterparty, ordered by how much value is tied up with each one rather
 * than by row count. Same load/select/confirm shape as MerchantGroupReviewCard, and calls the same
 * bulkRecategorize write path — see that component's own doc for the shape both share.
 *
 * Deliberately not a merge of the two cards: TransactionGroupingService's own doc explains why a
 * merchant-matched row never reaches this grouping, so the two cards partition the backlog rather
 * than double-showing a row under two different headers.
 *
 * identityIsStrong gates how confidently a group is presented — see CounterpartyIdentity's own doc
 * on why a name: key must never be shown as a resolved identity. A weak group still gets the
 * bulk-apply action (grouping value survives even when the identity is a guess), but is visually
 * marked as a probable match, not a confirmed one.
 */
export function CounterpartyGroupReviewCard() {
  const queryClient = useQueryClient();
  const prefersReducedMotion = useReducedMotion();
  const [groups, setGroups] = useState<CounterpartyGroup[]>([]);
  const [picks, setPicks] = useState<Record<string, string>>({});
  const [creatingFor, setCreatingFor] = useState<string | null>(null);
  const [pendingText, setPendingText] = useState<Record<string, string>>({});
  const [applying, setApplying] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());

  function load() {
    setLoading(true);
    transactionsApi.groupsNeedsReviewByCounterparty()
      .then(setGroups)
      .catch(() => {})
      .finally(() => setLoading(false));
  }
  useEffect(load, []);

  function toggleExpanded(key: string) {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  }

  async function apply(group: CounterpartyGroup) {
    const category = picks[group.counterpartyKey];
    if (!category) return;
    setApplying(group.counterpartyKey);
    setError(null);
    try {
      await transactionsApi.bulkRecategorize(group.transactionIds, category);
      setGroups((prev) => prev.filter((g) => g.counterpartyKey !== group.counterpartyKey));
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
        <Wallet size={17} className="text-primary" />
        <h2 className="font-semibold text-ink text-sm">Categorize by who you paid</h2>
      </div>
      <p className="text-xs text-muted mb-4">
        Sorted by how much money is tied up with each person or business — the biggest ones first.
      </p>
      {loading ? (
        <ReviewCardSkeleton />
      ) : (
        <>
          {error && <p className="text-xs text-danger mb-3">{error}</p>}
          <div className="space-y-3">
            {groups.map((g) => {
              const isExpanded = expanded.has(g.counterpartyKey);
              return (
                <div key={g.counterpartyKey} className="border border-border rounded-lg p-3">
                  <div className={`flex gap-3 flex-wrap sm:flex-nowrap ${creatingFor === g.counterpartyKey ? 'items-start' : 'items-center'}`}>
                    <button
                      type="button"
                      onClick={() => toggleExpanded(g.counterpartyKey)}
                      aria-expanded={isExpanded}
                      aria-label={`${isExpanded ? 'Hide' : 'Show'} the ${g.transactionIds.length} transactions for ${g.label}`}
                      className="min-w-0 flex-1 flex items-center gap-2 text-left"
                    >
                      <ChevronDown
                        size={14}
                        className={`text-muted flex-shrink-0 transition-transform duration-200 ${isExpanded ? 'rotate-180' : ''}`}
                        aria-hidden="true"
                      />
                      <span className="min-w-0">
                        <span className="flex items-center gap-1.5">
                          <p className="text-sm font-medium text-ink truncate">{g.label}</p>
                          {/* Type badge, styled like the row-level one Ledger.tsx already shows --
                              this is context, not a resolved identity, same reasoning as there.
                              No direction composed in (a group can carry both sent and received
                              rows), so this shows only the noun, never "sent to"/"paid". */}
                          <span className="text-[9px] uppercase bg-gray-200 text-gray-500 px-1 py-0.5 rounded flex-shrink-0">
                            {g.counterpartyType === 'PERSON' ? 'Person' : 'Business'}
                          </span>
                          {/* A name: key is a guess -- CounterpartyIdentity's own doc is explicit
                              that it must never be presented as a resolved identity. This group
                              still gets the bulk-apply action (grouping survives a guessed key),
                              but is visibly marked probable rather than confirmed. */}
                          {!g.identityIsStrong && (
                            <span
                              className="text-[9px] uppercase bg-warning-bg text-warning px-1 py-0.5 rounded flex-shrink-0"
                              title="Grouped by a guessed name, not a confirmed payment handle -- double-check before applying"
                            >
                              Probable
                            </span>
                          )}
                        </span>
                        <p className="text-[11px] text-muted">
                          {fmt(g.totalValue)} · {g.transactionIds.length} transactions
                        </p>
                      </span>
                    </button>
                    <div className={`flex-shrink-0 ${creatingFor === g.counterpartyKey ? 'w-64' : 'w-40'}`}>
                      {creatingFor === g.counterpartyKey ? (
                        <CategoryCreateEditPanel
                          mode="create"
                          initialName={pendingText[g.counterpartyKey] ?? ''}
                          onSaved={(c) => { setPicks((p) => ({ ...p, [g.counterpartyKey]: c.name })); setCreatingFor(null); }}
                          onCancel={() => setCreatingFor(null)}
                        />
                      ) : (
                        <CategoryCombobox
                          value={picks[g.counterpartyKey] ?? ''}
                          onChange={(name) => setPicks((p) => ({ ...p, [g.counterpartyKey]: name }))}
                          onCreateNew={(text) => { setPendingText((p) => ({ ...p, [g.counterpartyKey]: text })); setCreatingFor(g.counterpartyKey); }}
                        />
                      )}
                    </div>
                    <motion.button
                      whileTap={{ scale: 0.96 }}
                      onClick={() => apply(g)}
                      disabled={!picks[g.counterpartyKey] || applying === g.counterpartyKey}
                      className="bg-primary text-on-primary text-xs font-medium rounded-lg px-3 py-1.5 flex items-center gap-1 flex-shrink-0 disabled:opacity-40"
                    >
                      <Check size={13} />
                      {applying === g.counterpartyKey ? 'Applying…' : `Apply to ${g.transactionIds.length} transactions`}
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
