import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useQuery, useQueryClient, keepPreviousData } from '@tanstack/react-query';
import { Pencil, Trash2, X, ChevronLeft, ChevronRight, HelpCircle } from 'lucide-react';
import { transactionsApi, categoriesApi, type TransactionFilters, type UpdateTransactionPayload, type TransactionExplanation } from '../api/endpoints';
import { AskOnceCard } from '../components/AskOnceCard';
import type { Transaction } from '../types';

function fmt(n: number) {
  // Negative amounts (e.g. a month where spend exceeded income) must render as "-₹500",
  // not "₹-500" -- string concatenation put the currency symbol before the sign.
  return (n < 0 ? '-₹' : '₹') + Math.round(Math.abs(n)).toLocaleString('en-IN');
}

// Small debounce hook so typing in the search box doesn't fire a query per keystroke —
// the debounced value becomes part of the query key, so TanStack Query only refetches
// once typing settles, and caches each distinct filter combination it's already seen.
function useDebouncedValue<T>(value: T, delayMs: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delayMs);
    return () => clearTimeout(timer);
  }, [value, delayMs]);
  return debounced;
}

export default function Ledger() {
  // Seeds the search box from the TopBar's global search ("/app/transactions?q=..."), so
  // pressing Enter up there actually lands here with the term already applied rather than
  // just navigating to an empty ledger.
  const [searchParams] = useSearchParams();
  const [filters, setFilters] = useState<TransactionFilters>({ page: 0, size: 10, sortField: 'date', sortDir: 'desc' });
  const [keywordInput, setKeywordInput] = useState(() => searchParams.get('q') ?? '');
  const debouncedKeyword = useDebouncedValue(keywordInput, 300);
  const queryClient = useQueryClient();

  const [editing, setEditing] = useState<Transaction | null>(null);
  const [explaining, setExplaining] = useState<Transaction | null>(null);
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Ledger doesn't unmount between two TopBar searches fired while already on this page (same
  // route, just a new ?q=), so the useState initializer above only covers the first visit —
  // this covers every subsequent one.
  useEffect(() => {
    const q = searchParams.get('q');
    if (q !== null) setKeywordInput(q);
  }, [searchParams]);

  // Every other filter control already resets to page 0 on change (see the type/date filters
  // below) -- the search box was the one gap, since it goes through the debounce above rather
  // than a plain onChange. Without this, searching while on page 3 of the unfiltered list could
  // land on a now out-of-range page of the filtered one.
  useEffect(() => {
    setFilters((f) => (f.page === 0 ? f : { ...f, page: 0 }));
    // Only when the search term itself actually changes, not on every filters update (that would
    // fight with the other filters' own `page: 0` resets and the Previous/Next handlers below).
    // Safe to depend on debouncedKeyword alone: setFilters uses the functional-updater form
    // above, so it never needs `filters` itself in this array.
  }, [debouncedKeyword]);

  const activeFilters = { ...filters, keyword: debouncedKeyword || undefined };

  const { data: page, isLoading, isFetching } = useQuery({
    queryKey: ['transactions', activeFilters],
    queryFn: () => transactionsApi.search(activeFilters),
    placeholderData: keepPreviousData, // keep showing the old page while the new one loads, no flash-to-empty
  });
  const txns = page?.content ?? [];

  // Deleting a transaction can shrink the total below the page currently being viewed (e.g. the
  // only row left on the last page) -- without this, that page would just render empty with no
  // way back except manually clicking Previous. Keyed off the server's own totalPages/page in the
  // response, not local `filters.page`, so this only fires once the backend has actually confirmed
  // the current page no longer exists.
  useEffect(() => {
    if (page && page.totalPages > 0 && page.page > 0 && page.page >= page.totalPages) {
      setFilters((f) => ({ ...f, page: page.totalPages - 1 }));
    }
  }, [page]);

  // Editing/deleting a transaction can shift its own category totals, its account's balance,
  // budget progress, goals funded from it, and any AI insight built from spend patterns — same
  // cascading-refresh set Import.tsx already invalidates after a CSV confirm.
  function invalidateEverything() {
    // 'report'/'report-months' feed the Dashboard's Cash Flow Overview chart -- easy to miss
    // since Ledger doesn't render that chart itself, but an edit/delete here changes exactly
    // the per-month totals that chart is built from.
    ['transactions', 'dashboard-summary', 'accounts', 'recent-transactions', 'budgets', 'goals', 'insights', 'report-months', 'report']
      .forEach((key) => { void queryClient.invalidateQueries({ queryKey: [key] }); });
  }

  async function handleDelete(t: Transaction) {
    if (!confirm(`Delete "${t.description || t.merchant}" (${fmt(t.amount)})? This can't be undone.`)) return;
    setDeletingId(t.id);
    setError(null);
    try {
      await transactionsApi.remove(t.id);
      invalidateEverything();
    } catch (e: any) {
      setError(e.response?.data?.message ?? 'Could not delete this transaction.');
    } finally {
      setDeletingId(null);
    }
  }

  return (
    <div className="space-y-4">
      {/* Moved here from the Dashboard: transaction category review belongs with the
          transactions themselves, not mixed into an at-a-glance financial overview — see
          AskOnceCard's own doc comment for what it does. */}
      <AskOnceCard />

      {error && <p className="text-danger text-sm">{error}</p>}

      <div className="bg-card rounded p-4 shadow grid grid-cols-2 md:grid-cols-4 gap-2">
        <input
          placeholder="Search description, merchant, bank, account, branch, IFSC…"
          value={keywordInput}
          onChange={(e) => setKeywordInput(e.target.value)}
          className="bg-card text-ink border rounded px-2 py-1.5 text-sm"
        />
        <select className="bg-card text-ink border rounded px-2 py-1.5 text-sm" onChange={(e) => setFilters((f) => ({ ...f, type: e.target.value || undefined, page: 0 }))}>
          <option value="">All Types</option>
          <option value="INCOME">Income</option>
          <option value="EXPENSE">Expense</option>
        </select>
        <input type="date" className="bg-card text-ink border rounded px-2 py-1.5 text-sm" onChange={(e) => setFilters((f) => ({ ...f, dateFrom: e.target.value || undefined, page: 0 }))} />
        <input type="date" className="bg-card text-ink border rounded px-2 py-1.5 text-sm" onChange={(e) => setFilters((f) => ({ ...f, dateTo: e.target.value || undefined, page: 0 }))} />
      </div>

      <div className="bg-card rounded shadow overflow-x-auto relative">
        {isFetching && !isLoading && (
          <div className="absolute top-2 right-3 text-[10px] uppercase text-primary">Refreshing…</div>
        )}
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-[10px] uppercase text-gray-500 border-b">
              <th className="p-2">Date</th>
              <th className="p-2">Description</th>
              <th className="p-2">Category</th>
              <th className="p-2">Amount</th>
              <th className="p-2">Status</th>
              <th className="p-2"></th>
            </tr>
          </thead>
          <tbody>
            {isLoading ? (
              <tr><td colSpan={6} className="p-4 text-center text-gray-500">Loading…</td></tr>
            ) : txns.length === 0 ? (
              <tr><td colSpan={6} className="p-4 text-center text-gray-500 italic">No transactions match these filters.</td></tr>
            ) : (
              txns.map((t) => (
                <tr key={t.id} className="border-b border-dashed">
                  <td className="p-2">{t.date}</td>
                  <td className="p-2">
                    {t.description || t.merchant}
                    {t.needsCategoryReview && <span className="text-[10px] uppercase bg-warning-bg text-warning px-1.5 py-0.5 rounded ml-1">needs review</span>}
                    {t.recurring && <span className="text-[10px] uppercase bg-primary/15 text-primary px-1.5 py-0.5 rounded ml-1">recurring</span>}
                  </td>
                  <td className="p-2 text-gray-500">
                    {t.categoryName}
                    <span
                      className={`text-[9px] uppercase ml-1.5 px-1 py-0.5 rounded ${t.categoryManuallySet ? 'bg-primary/15 text-primary' : 'bg-gray-200 text-gray-500'}`}
                      title={t.categoryManuallySet ? 'You set this category' : 'Automatically assigned by Finora'}
                    >
                      {t.categoryManuallySet ? 'Manual' : 'Auto'}
                    </span>
                    <button
                      type="button"
                      title="Why this category?"
                      onClick={() => setExplaining(t)}
                      className="inline-flex items-center justify-center w-4 h-4 ml-1 text-muted hover:text-ink align-middle"
                    >
                      <HelpCircle size={12} />
                    </button>
                  </td>
                  <td className={`p-2 ${t.type === 'INCOME' ? 'text-success' : 'text-danger'}`}>
                    {t.type === 'INCOME' ? '+' : '-'}{fmt(t.amount)}
                  </td>
                  <td className="p-2">
                    <span className="text-[10px] uppercase bg-primary/15 text-primary px-1.5 py-0.5 rounded">
                      {t.reconciliationStatus}
                    </span>
                  </td>
                  <td className="p-2">
                    <div className="flex items-center gap-1 justify-end">
                      <button
                        type="button"
                        title="Edit transaction"
                        onClick={() => setEditing(t)}
                        className="w-7 h-7 rounded border border-border flex items-center justify-center text-muted hover:text-ink hover:bg-bg"
                      >
                        <Pencil size={13} />
                      </button>
                      <button
                        type="button"
                        title="Delete transaction"
                        disabled={deletingId === t.id}
                        onClick={() => handleDelete(t)}
                        className="w-7 h-7 rounded border border-border flex items-center justify-center text-danger hover:bg-danger-bg disabled:opacity-40"
                      >
                        <Trash2 size={13} />
                      </button>
                    </div>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {page && page.totalElements > 0 && (
        <div className="flex items-center justify-between text-xs text-muted px-1">
          <p>
            Showing <span className="text-ink font-medium">{page.page * page.size + 1}</span>
            {'–'}
            <span className="text-ink font-medium">{Math.min((page.page + 1) * page.size, page.totalElements)}</span>
            {' of '}
            <span className="text-ink font-medium">{page.totalElements.toLocaleString('en-IN')}</span>
          </p>
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => setFilters((f) => ({ ...f, page: Math.max(0, (f.page ?? 0) - 1) }))}
              disabled={page.page === 0 || isFetching}
              className="w-7 h-7 rounded border border-border flex items-center justify-center text-muted hover:text-ink hover:bg-bg disabled:opacity-40"
            >
              <ChevronLeft size={14} />
            </button>
            <span className="text-ink">Page {page.page + 1} of {Math.max(1, page.totalPages)}</span>
            <button
              type="button"
              onClick={() => setFilters((f) => ({ ...f, page: (f.page ?? 0) + 1 }))}
              disabled={page.page + 1 >= page.totalPages || isFetching}
              className="w-7 h-7 rounded border border-border flex items-center justify-center text-muted hover:text-ink hover:bg-bg disabled:opacity-40"
            >
              <ChevronRight size={14} />
            </button>
          </div>
        </div>
      )}

      {editing && (
        <EditTransactionModal
          transaction={editing}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            invalidateEverything();
          }}
        />
      )}

      {explaining && (
        <ExplanationModal transaction={explaining} onClose={() => setExplaining(null)} />
      )}
    </div>
  );
}

// "Why this category?" -- fetched on demand rather than carried on every row, since most rows
// are never expanded. Every branch below is Finora's own real categorization decision read back
// out, not a new guess made for this panel -- see TransactionExplanationDto's own doc comment.
function ExplanationModal({ transaction, onClose }: { transaction: Transaction; onClose: () => void }) {
  const [explanation, setExplanation] = useState<TransactionExplanation | null>(null);
  const [loadError, setLoadError] = useState(false);

  useEffect(() => {
    let cancelled = false;
    transactionsApi.explanation(transaction.id)
      .then((result) => { if (!cancelled) setExplanation(result); })
      .catch(() => { if (!cancelled) setLoadError(true); });
    return () => { cancelled = true; };
  }, [transaction.id]);

  return (
    <>
      <div className="fixed inset-0 bg-black/40 z-30" onClick={onClose} />
      <div className="fixed inset-0 z-40 flex items-center justify-center p-4 pointer-events-none">
        <div className="bg-card border border-border rounded-xl2 shadow-soft w-full max-w-sm p-5 pointer-events-auto">
          <div className="flex items-center justify-between mb-3">
            <h3 className="font-semibold text-ink text-sm">Why this category?</h3>
            <button type="button" onClick={onClose} aria-label="Close" className="text-muted hover:text-ink">
              <X size={18} />
            </button>
          </div>

          <p className="text-xs text-muted mb-3">
            {transaction.description || transaction.merchant} · {transaction.categoryName}
          </p>

          {loadError ? (
            <p className="text-danger text-xs">Couldn't load this explanation — please try again.</p>
          ) : !explanation ? (
            <p className="text-muted text-xs">Loading…</p>
          ) : (
            <div className="space-y-2">
              <p className="text-ink text-sm">{explanation.summary}</p>
              {explanation.evidence.length > 0 && (
                <ul className="list-disc list-inside space-y-1">
                  {explanation.evidence.map((line, i) => (
                    <li key={i} className="text-xs text-muted">{line}</li>
                  ))}
                </ul>
              )}
            </div>
          )}
        </div>
      </div>
    </>
  );
}

function EditTransactionModal({
  transaction, onClose, onSaved,
}: {
  transaction: Transaction;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [date, setDate] = useState(transaction.date);
  const [description, setDescription] = useState(transaction.description ?? '');
  const [merchant, setMerchant] = useState(transaction.merchant ?? '');
  const [amount, setAmount] = useState(String(transaction.amount));
  const [type, setType] = useState<'INCOME' | 'EXPENSE'>(transaction.type);
  const [category, setCategory] = useState(transaction.categoryName);
  const [notes, setNotes] = useState(transaction.notes ?? '');
  const [tagsInput, setTagsInput] = useState((transaction.tags ?? []).join(', '));
  const [categories, setCategories] = useState<string[]>([]);
  const [categoriesFailed, setCategoriesFailed] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    // Bug fix: this was `.catch(() => {})`, which left the dropdown empty and made "the request
    // failed" look exactly like "you have no categories" -- and the user's only recourse for an
    // apparently-empty list is to go create categories that already exist. The edit modal still
    // opens and still saves (the current category is preserved by the option below), so this is a
    // notice rather than a blocker.
    categoriesApi.list()
      .then((cats) => setCategories(cats.map((c) => c.name)))
      .catch(() => setCategoriesFailed(true));
  }, []);

  async function save() {
    setSaving(true);
    setError(null);
    try {
      const payload: UpdateTransactionPayload = {
        date,
        description,
        merchant,
        amount: parseFloat(amount),
        type,
        categoryName: category,
        // Sent as the literal current value (never funneled through `|| null`), same as
        // description/merchant above -- the backend's UpdateRequest treats a null field as
        // "leave unchanged," so using `|| null` here would make clearing existing notes down to
        // empty silently no-op instead of actually clearing them.
        notes: notes.trim(),
        tags: tagsInput.split(',').map((s) => s.trim()).filter(Boolean),
      };
      await transactionsApi.update(transaction.id, payload);
      onSaved();
    } catch (e: any) {
      setError(e.response?.data?.message ?? 'Could not save these changes.');
    } finally {
      setSaving(false);
    }
  }

  return (
    <>
      <div className="fixed inset-0 bg-black/40 z-30" onClick={onClose} />
      <div className="fixed inset-0 z-40 flex items-center justify-center p-4 pointer-events-none">
        <div className="bg-card border border-border rounded-xl2 shadow-soft w-full max-w-lg max-h-[85vh] overflow-y-auto p-5 pointer-events-auto">
          <div className="flex items-center justify-between mb-4">
            <h3 className="font-semibold text-ink text-sm">Edit Transaction</h3>
            <button type="button" onClick={onClose} className="text-muted hover:text-ink">
              <X size={18} />
            </button>
          </div>

          {error && <p className="text-danger text-xs mb-3">{error}</p>}

          <div className="grid grid-cols-2 gap-3 text-sm">
            <div>
              <label htmlFor="edit-txn-date" className="block text-[11px] uppercase text-muted mb-1">Date</label>
              <input id="edit-txn-date" type="date" value={date} onChange={(e) => setDate(e.target.value)} className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm w-full" />
            </div>
            <div>
              <label htmlFor="edit-txn-type" className="block text-[11px] uppercase text-muted mb-1">Type</label>
              <select id="edit-txn-type" value={type} onChange={(e) => setType(e.target.value as 'INCOME' | 'EXPENSE')} className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm w-full">
                <option value="INCOME">Income</option>
                <option value="EXPENSE">Expense</option>
              </select>
            </div>
            <div className="col-span-2">
              <label htmlFor="edit-txn-description" className="block text-[11px] uppercase text-muted mb-1">Description</label>
              <input id="edit-txn-description" value={description} onChange={(e) => setDescription(e.target.value)} className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm w-full" />
            </div>
            <div className="col-span-2">
              <label htmlFor="edit-txn-merchant" className="block text-[11px] uppercase text-muted mb-1">Merchant</label>
              <input id="edit-txn-merchant" value={merchant} onChange={(e) => setMerchant(e.target.value)} className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm w-full" />
            </div>
            <div>
              <label htmlFor="edit-txn-amount" className="block text-[11px] uppercase text-muted mb-1">Amount</label>
              <input id="edit-txn-amount" type="number" min="0.01" step="0.01" value={amount} onChange={(e) => setAmount(e.target.value)} className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm w-full" />
            </div>
            <div>
              <label htmlFor="edit-txn-category" className="block text-[11px] uppercase text-muted mb-1">Category</label>
              <select id="edit-txn-category" value={category} onChange={(e) => setCategory(e.target.value)} className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm w-full">
                {!categories.includes(category) && <option value={category}>{category}</option>}
                {categories.map((c) => <option key={c} value={c}>{c}</option>)}
              </select>
              {categoriesFailed && (
                <p className="text-[11px] text-warning mt-1">
                  Couldn't load your categories — only the current one is shown.
                </p>
              )}
            </div>
            <div className="col-span-2">
              <label htmlFor="edit-txn-notes" className="block text-[11px] uppercase text-muted mb-1">Notes</label>
              <textarea id="edit-txn-notes" value={notes} onChange={(e) => setNotes(e.target.value)} rows={2} className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm w-full" />
            </div>
            <div className="col-span-2">
              <label htmlFor="edit-txn-tags" className="block text-[11px] uppercase text-muted mb-1">Tags (comma-separated)</label>
              <input id="edit-txn-tags" value={tagsInput} onChange={(e) => setTagsInput(e.target.value)} placeholder="e.g. shared, recurring" className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm w-full" />
            </div>
          </div>

          <div className="flex gap-3 mt-5">
            <button
              onClick={save}
              disabled={saving || !description.trim() || !amount || !(parseFloat(amount) > 0)}
              className="bg-primary text-white hover:bg-primary-dark px-4 py-2 rounded-lg text-xs font-semibold disabled:opacity-50"
            >
              {saving ? 'Saving…' : 'Save changes'}
            </button>
            <button onClick={onClose} className="border border-border text-ink px-4 py-2 rounded-lg text-xs font-semibold">
              Cancel
            </button>
          </div>
        </div>
      </div>
    </>
  );
}
