import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowLeft, Check, X } from 'lucide-react';
import { gmailApi, categoriesApi, type GmailReviewItem } from '../api/endpoints';
import { formatDayMonthYear } from '../components/AccountUI';

// C5.4, D-15: a true per-receipt review queue, not the generic "Continue previous import" list
// CSV/PDF sessions share -- each Gmail-sourced ImportSession is a single-row session
// (GmailStagingBridge's own contract), so this page is the receipt-shaped view of exactly that
// data, backed entirely by GmailReviewService's approve()/reject() (which still confirm/discard
// the underlying session -- see that class's own doc comment for why the two are the same
// operation for Gmail specifically).

function formatAmount(amount: number): string {
  return '₹' + amount.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function confidenceLabel(confidence: number | null): string | null {
  if (confidence === null) return null;
  return `${Math.round(confidence * 100)}% confidence`;
}

export default function GmailReview() {
  const navigate = useNavigate();
  const [items, setItems] = useState<GmailReviewItem[]>([]);
  const [categories, setCategories] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);
  const [editedCategory, setEditedCategory] = useState<Record<string, string>>({});
  const [busyId, setBusyId] = useState<string | null>(null);
  const [rowError, setRowError] = useState<Record<string, string>>({});

  function load() {
    setLoading(true);
    setLoadError(false);
    gmailApi.reviewQueue()
      .then(setItems)
      .catch(() => setLoadError(true))
      .finally(() => setLoading(false));
  }

  useEffect(() => {
    load();
    categoriesApi.list().then((cats) => setCategories(cats.map((c) => c.name))).catch(() => {});
  }, []);

  async function approve(item: GmailReviewItem) {
    setBusyId(item.sessionId);
    setRowError((prev) => ({ ...prev, [item.sessionId]: '' }));
    try {
      const category = editedCategory[item.sessionId];
      await gmailApi.approve(item.sessionId, category && category !== item.category ? category : undefined);
      setItems((prev) => prev.filter((i) => i.sessionId !== item.sessionId));
    } catch {
      setRowError((prev) => ({ ...prev, [item.sessionId]: "Couldn't approve this receipt -- try again." }));
    } finally {
      setBusyId(null);
    }
  }

  async function reject(item: GmailReviewItem) {
    setBusyId(item.sessionId);
    setRowError((prev) => ({ ...prev, [item.sessionId]: '' }));
    try {
      await gmailApi.reject(item.sessionId);
      setItems((prev) => prev.filter((i) => i.sessionId !== item.sessionId));
    } catch {
      setRowError((prev) => ({ ...prev, [item.sessionId]: "Couldn't discard this receipt -- try again." }));
    } finally {
      setBusyId(null);
    }
  }

  return (
    <div className="space-y-6 max-w-3xl">
      <div>
        <button
          type="button"
          onClick={() => navigate('/app/settings')}
          className="text-xs text-muted hover:text-ink inline-flex items-center gap-1 mb-3"
        >
          <ArrowLeft size={13} /> Settings
        </button>
        <h1 className="font-serif text-2xl font-semibold text-ink">Gmail Transactions</h1>
        <p className="text-sm text-muted mt-1">
          Receipts Finora found in your inbox. Nothing here is added to your ledger until you approve it.
        </p>
      </div>

      {loading ? (
        <p className="text-muted text-sm">Loading…</p>
      ) : loadError ? (
        <p className="text-sm text-danger">Couldn't load your Gmail receipts — please try again later.</p>
      ) : items.length === 0 ? (
        <p className="text-sm text-muted italic">Nothing waiting for review right now.</p>
      ) : (
        <div className="space-y-3">
          {items.map((item) => (
            <div key={item.sessionId} className="bg-card border border-border rounded-lg p-4">
              <div className="flex items-start justify-between gap-3">
                <div>
                  <p className="text-ink font-medium">{item.merchant}</p>
                  <p className="text-xs text-muted mt-0.5">{formatDayMonthYear(item.date)}</p>
                </div>
                <div className="text-right flex-shrink-0">
                  <p className="text-ink font-semibold">{formatAmount(item.amount)}</p>
                  {confidenceLabel(item.confidence) && (
                    <p className="text-[11px] text-muted mt-0.5">{confidenceLabel(item.confidence)}</p>
                  )}
                </div>
              </div>

              {item.reasoning && (
                <p className="text-xs text-muted mt-2 italic">{item.reasoning}</p>
              )}

              <div className="mt-3">
                <label htmlFor={`category-${item.sessionId}`} className="block text-[11px] uppercase text-muted mb-1">
                  Category
                </label>
                <select
                  id={`category-${item.sessionId}`}
                  value={editedCategory[item.sessionId] ?? item.category}
                  onChange={(e) => setEditedCategory((prev) => ({ ...prev, [item.sessionId]: e.target.value }))}
                  className="bg-bg text-ink border border-border rounded-lg px-3 py-1.5 text-sm w-full max-w-xs"
                >
                  {!categories.includes(item.category) && <option value={item.category}>{item.category}</option>}
                  {categories.map((c) => (
                    <option key={c} value={c}>{c}</option>
                  ))}
                </select>
              </div>

              {rowError[item.sessionId] && (
                <p className="text-xs text-danger mt-2">{rowError[item.sessionId]}</p>
              )}

              <div className="flex items-center gap-2 mt-3 pt-3 border-t border-border">
                <button
                  type="button"
                  disabled={busyId === item.sessionId}
                  onClick={() => approve(item)}
                  className="bg-primary text-on-primary hover:bg-primary-dark disabled:opacity-50 rounded-lg px-3 py-1.5 text-xs uppercase font-medium inline-flex items-center gap-1.5"
                >
                  <Check size={13} /> Approve
                </button>
                <button
                  type="button"
                  disabled={busyId === item.sessionId}
                  onClick={() => reject(item)}
                  className="border border-border rounded-lg px-3 py-1.5 text-xs uppercase font-medium text-ink hover:bg-black/5 disabled:opacity-50 inline-flex items-center gap-1.5"
                >
                  <X size={13} /> Reject
                </button>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
