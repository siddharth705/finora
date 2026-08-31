import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { X } from 'lucide-react';
import { accountsApi, categoriesApi, transactionsApi, type CreateTransactionPayload } from '../api/endpoints';

/**
 * Wires up TransactionController.create() / transactionsApi.create() -- both already existed,
 * already worked, and had zero call sites anywhere in the frontend before D-21 (confirmed via a
 * repo-wide grep during that scoping pass). Deliberately close to Ledger.tsx's own
 * EditTransactionModal (same field layout, same category-loading pattern) rather than inventing a
 * new convention, minus the fields CreateRequest doesn't have (merchant is derived server-side
 * from description; notes isn't part of creation) and plus the one it needs that Update doesn't:
 * which account this goes on, since a transaction always belongs to one.
 *
 * A shared component (not page-local, unlike its original D-21 home inside Dashboard.tsx) since
 * TopBar's own global "Add Transaction" button needs the identical flow -- self-fetches its own
 * accounts/categories via the SAME query keys Dashboard.tsx already uses (['accounts'],
 * ['categories']), so TanStack Query dedupes the request into whichever of those is already
 * in-flight or cached rather than firing a second one just because this mounted from a different
 * page.
 */
export function AddTransactionModal({ onClose, onSaved }: { onClose: () => void; onSaved: () => void }) {
  const accountsQ = useQuery({ queryKey: ['accounts'], queryFn: () => accountsApi.list() });
  // Same "notice, not a blocker" reasoning as EditTransactionModal's identical effect in
  // Ledger.tsx -- a failed categories fetch isn't the same thing as "you have no categories," and
  // the form still works with categoryName left blank (TransactionService.create() takes its own
  // auto-categorization path when it's null).
  const categoriesQ = useQuery({ queryKey: ['categories'], queryFn: () => categoriesApi.list(), retry: false });

  const accounts = accountsQ.data ?? [];
  const categories = (categoriesQ.data ?? []).map((c) => c.name);

  const [accountId, setAccountId] = useState<string | null>(null);
  const [date, setDate] = useState(() => new Date().toISOString().slice(0, 10));
  const [description, setDescription] = useState('');
  const [amount, setAmount] = useState('');
  const [type, setType] = useState<'INCOME' | 'EXPENSE'>('EXPENSE');
  const [category, setCategory] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // accountId starts null (not accounts[0]?.id) specifically because accounts loads
  // asynchronously -- seeding it eagerly would freeze in the empty string the very first render
  // sees, before the real list ever arrives. selectedAccountId falls back to the first loaded
  // account only once there's something to fall back to.
  const selectedAccountId = accountId ?? accounts[0]?.id ?? '';
  const hasAccount = accountsQ.isSuccess && accounts.length > 0;
  const canSave = hasAccount && !!selectedAccountId && description.trim().length > 0 && !!amount && parseFloat(amount) > 0;

  async function save() {
    if (!canSave) return;
    setSaving(true);
    setError(null);
    try {
      const payload: CreateTransactionPayload = {
        accountId: selectedAccountId,
        date,
        description: description.trim(),
        amount: parseFloat(amount),
        type,
        categoryName: category || null,
        // Explicit [], not omitted -- Transaction.tags is typed string[] (non-nullable)
        // everywhere it's read, same reason EditTransactionModal always sends a real array
        // rather than relying on the field being optional on the wire.
        tags: [],
      };
      await transactionsApi.create(payload);
      onSaved();
    } catch (e: any) {
      setError(e.response?.data?.message ?? 'Could not add this transaction.');
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
            <h3 className="font-semibold text-ink text-sm">Add Transaction</h3>
            <button type="button" onClick={onClose} aria-label="Close" className="text-muted hover:text-ink">
              <X size={18} />
            </button>
          </div>

          {accountsQ.isLoading ? (
            <p className="text-sm text-muted">Loading your accounts…</p>
          ) : !hasAccount ? (
            // A transaction always belongs to an account (TransactionService.create()'s
            // getOwnedAccount call has nothing to attach to otherwise) -- Setup.tsx is the
            // existing manual-account-creation flow; this doesn't duplicate it.
            <div className="text-sm text-ink">
              <p className="mb-3">You'll need an account before adding a transaction by hand.</p>
              <Link
                to="/app/setup"
                className="inline-block bg-primary text-on-primary hover:bg-primary-dark px-4 py-2 rounded-lg text-xs font-semibold"
              >
                Add an account
              </Link>
            </div>
          ) : (
            <>
              {error && <p className="text-danger text-xs mb-3">{error}</p>}
              <div className="grid grid-cols-2 gap-3 text-sm">
                <div className="col-span-2">
                  <label htmlFor="add-txn-account" className="block text-[11px] uppercase text-muted mb-1">Account</label>
                  <select
                    id="add-txn-account"
                    value={selectedAccountId}
                    onChange={(e) => setAccountId(e.target.value)}
                    className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm w-full"
                  >
                    {accounts.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
                  </select>
                </div>
                <div>
                  <label htmlFor="add-txn-date" className="block text-[11px] uppercase text-muted mb-1">Date</label>
                  <input id="add-txn-date" type="date" value={date} onChange={(e) => setDate(e.target.value)} className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm w-full" />
                </div>
                <div>
                  <label htmlFor="add-txn-type" className="block text-[11px] uppercase text-muted mb-1">Type</label>
                  <select id="add-txn-type" value={type} onChange={(e) => setType(e.target.value as 'INCOME' | 'EXPENSE')} className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm w-full">
                    <option value="EXPENSE">Expense</option>
                    <option value="INCOME">Income</option>
                  </select>
                </div>
                <div className="col-span-2">
                  <label htmlFor="add-txn-description" className="block text-[11px] uppercase text-muted mb-1">Description</label>
                  <input id="add-txn-description" value={description} onChange={(e) => setDescription(e.target.value)} placeholder="e.g. Groceries at the market" className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm w-full" />
                </div>
                <div>
                  <label htmlFor="add-txn-amount" className="block text-[11px] uppercase text-muted mb-1">Amount</label>
                  <input id="add-txn-amount" type="number" min="0.01" step="0.01" value={amount} onChange={(e) => setAmount(e.target.value)} placeholder="0.00" className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm w-full" />
                </div>
                <div>
                  <label htmlFor="add-txn-category" className="block text-[11px] uppercase text-muted mb-1">Category</label>
                  <select id="add-txn-category" value={category} onChange={(e) => setCategory(e.target.value)} className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm w-full">
                    <option value="">Let Fynora categorize it</option>
                    {categories.map((c) => <option key={c} value={c}>{c}</option>)}
                  </select>
                  {categoriesQ.isError && (
                    <p className="text-[11px] text-warning mt-1">Couldn't load categories — leave blank to auto-categorize.</p>
                  )}
                </div>
              </div>

              <div className="flex gap-3 mt-5">
                <button
                  onClick={save}
                  disabled={saving || !canSave}
                  className="bg-primary text-on-primary hover:bg-primary-dark px-4 py-2 rounded-lg text-xs font-semibold disabled:opacity-50"
                >
                  {saving ? 'Adding…' : 'Add transaction'}
                </button>
                <button onClick={onClose} className="border border-border text-ink px-4 py-2 rounded-lg text-xs font-semibold">
                  Cancel
                </button>
              </div>
            </>
          )}
        </div>
      </div>
    </>
  );
}
