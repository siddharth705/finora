import { useEffect, useState } from 'react';
import { Store, X, History, GitMerge, Pencil, Check } from 'lucide-react';
import { merchantsApi } from '../api/endpoints';
import type { Merchant, MerchantAuditEntry } from '../types';

function confidenceColor(confidence: number | null) {
  // Color-coding per docs/financial-intelligence-engine-spec.md §6.1: green >=90%, amber
  // 60-89%, red <60%.
  if (confidence == null) return 'bg-gray-300 text-gray-600';
  if (confidence >= 90) return 'bg-success/15 text-success';
  if (confidence >= 60) return 'bg-primary/15 text-primary-dark';
  return 'bg-danger/15 text-danger';
}

export default function Merchants() {
  const [merchants, setMerchants] = useState<Merchant[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [renamingId, setRenamingId] = useState<string | null>(null);
  const [renameValue, setRenameValue] = useState('');

  const [mergeTarget, setMergeTarget] = useState<Merchant | null>(null);
  const [mergeSourceId, setMergeSourceId] = useState('');
  const [mergeStep, setMergeStep] = useState<'select' | 'confirm'>('select');
  const [merging, setMerging] = useState(false);

  const [auditFor, setAuditFor] = useState<Merchant | null>(null);
  const [auditEntries, setAuditEntries] = useState<MerchantAuditEntry[]>([]);
  const [auditLoading, setAuditLoading] = useState(false);
  const [undoing, setUndoing] = useState(false);

  function load() {
    setLoading(true);
    merchantsApi.list()
      .then(setMerchants)
      .catch((e) => setError(e.response?.data?.message ?? 'Could not load merchants.'))
      .finally(() => setLoading(false));
  }
  useEffect(load, []);

  function startRename(m: Merchant) {
    setRenamingId(m.id);
    setRenameValue(m.canonicalName);
  }

  async function saveRename(id: string) {
    if (!renameValue.trim()) return;
    try {
      await merchantsApi.update(id, { canonicalName: renameValue.trim() });
      setRenamingId(null);
      load();
    } catch (e: any) {
      setError(e.response?.data?.message ?? 'Could not rename this merchant.');
    }
  }

  function openMerge(m: Merchant) {
    setMergeTarget(m);
    setMergeSourceId('');
    setMergeStep('select');
  }

  const mergeSource = merchants.find((m) => m.id === mergeSourceId) ?? null;

  async function confirmMerge() {
    if (!mergeTarget || !mergeSourceId) return;
    setMerging(true);
    try {
      await merchantsApi.merge(mergeTarget.id, mergeSourceId);
      setMergeTarget(null);
      load();
    } catch (e: any) {
      setError(e.response?.data?.message ?? 'Merge failed -- nothing was changed.');
    } finally {
      setMerging(false);
    }
  }

  function openAudit(m: Merchant) {
    setAuditFor(m);
    setAuditLoading(true);
    merchantsApi.audit(m.id)
      .then(setAuditEntries)
      .catch(() => setAuditEntries([]))
      .finally(() => setAuditLoading(false));
  }

  const canUndo = auditEntries.length > 0
    && auditEntries[0].action !== 'UNDONE' && auditEntries[0].action !== 'MERGED';

  async function undoLast() {
    if (!auditFor) return;
    setUndoing(true);
    try {
      await merchantsApi.undo(auditFor.id);
      openAudit(auditFor); // reload the audit trail (now including the new UNDONE entry)
      load();
    } catch (e: any) {
      setError(e.response?.data?.message ?? 'Could not undo the last change.');
    } finally {
      setUndoing(false);
    }
  }

  if (loading) return <p className="text-sm text-gray-500">Loading merchants…</p>;

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2">
        <Store size={20} className="text-primary" />
        <h1 className="text-lg font-semibold">Merchants</h1>
      </div>
      {error && (
        <div className="bg-danger/10 text-danger text-sm rounded p-3 flex justify-between items-center">
          <span>{error}</span>
          <button onClick={() => setError(null)}><X size={14} /></button>
        </div>
      )}

      <div className="bg-card rounded shadow overflow-hidden">
        {merchants.length === 0 ? (
          <p className="text-sm italic text-gray-500 p-4">
            No merchants resolved yet -- import a statement or add transactions to get started.
          </p>
        ) : (
          <table className="w-full text-sm">
            <thead className="bg-black/5 text-xs uppercase text-gray-500">
              <tr>
                <th className="text-left px-4 py-2.5">Merchant</th>
                <th className="text-left px-4 py-2.5">Top Category</th>
                <th className="text-left px-4 py-2.5">Confirmations</th>
                <th className="text-right px-4 py-2.5">Actions</th>
              </tr>
            </thead>
            <tbody>
              {merchants.map((m) => (
                <tr key={m.id} className="border-t border-black/5">
                  <td className="px-4 py-2.5">
                    {renamingId === m.id ? (
                      <div className="flex items-center gap-1.5">
                        <input
                          autoFocus
                          value={renameValue}
                          onChange={(e) => setRenameValue(e.target.value)}
                          onKeyDown={(e) => e.key === 'Enter' && saveRename(m.id)}
                          className="bg-card text-ink border rounded px-2 py-1 text-sm w-40"
                        />
                        <button onClick={() => saveRename(m.id)} className="text-success"><Check size={16} /></button>
                        <button onClick={() => setRenamingId(null)} className="text-gray-400"><X size={16} /></button>
                      </div>
                    ) : (
                      <span className="font-medium">{m.canonicalName}</span>
                    )}
                  </td>
                  <td className="px-4 py-2.5">
                    {m.topCategory ? (
                      <span className={`px-2 py-0.5 rounded-full text-xs font-medium ${confidenceColor(m.topCategoryConfidence)}`}>
                        {m.topCategory} · {m.topCategoryConfidence}%
                      </span>
                    ) : (
                      <span className="text-xs text-gray-400 italic">No confirmations yet</span>
                    )}
                  </td>
                  <td className="px-4 py-2.5 text-gray-500">
                    {m.distribution.reduce((sum, d) => sum + d.confirmationCount, 0)}
                  </td>
                  <td className="px-4 py-2.5">
                    <div className="flex justify-end gap-3 text-gray-400">
                      <button title="Rename" onClick={() => startRename(m)} className="hover:text-primary"><Pencil size={15} /></button>
                      <button title="Merge into another merchant" onClick={() => openMerge(m)} className="hover:text-primary"><GitMerge size={15} /></button>
                      <button title="Audit history" onClick={() => openAudit(m)} className="hover:text-primary"><History size={15} /></button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {/* Merge flow -- two-step per spec §6.3: select a second merchant, then preview the
          combined effect before confirming, since merging deletes the absorbed merchant's row. */}
      {mergeTarget && (
        <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-30" onClick={() => setMergeTarget(null)}>
          <div className="bg-card rounded shadow-xl p-5 w-[440px]" onClick={(e) => e.stopPropagation()}>
            <h2 className="font-semibold mb-3">Merge into "{mergeTarget.canonicalName}"</h2>

            {mergeStep === 'select' ? (
              <>
                <label className="block text-xs uppercase text-gray-500 mb-1">Merchant to absorb</label>
                <select
                  value={mergeSourceId}
                  onChange={(e) => setMergeSourceId(e.target.value)}
                  className="bg-card text-ink border rounded px-2 py-1.5 text-sm w-full mb-4"
                >
                  <option value="">Select a merchant…</option>
                  {merchants.filter((m) => m.id !== mergeTarget.id).map((m) => (
                    <option key={m.id} value={m.id}>{m.canonicalName}</option>
                  ))}
                </select>
                <div className="flex justify-end gap-2">
                  <button onClick={() => setMergeTarget(null)} className="px-3 py-1.5 text-sm rounded border">Cancel</button>
                  <button
                    disabled={!mergeSourceId}
                    onClick={() => setMergeStep('confirm')}
                    className="px-3 py-1.5 text-sm rounded bg-primary text-white disabled:opacity-50"
                  >
                    Next
                  </button>
                </div>
              </>
            ) : (
              <>
                <p className="text-sm text-gray-600 mb-3">
                  <strong>"{mergeSource?.canonicalName}"</strong> will be permanently absorbed into{' '}
                  <strong>"{mergeTarget.canonicalName}"</strong>. Its transactions, aliases, and
                  learning history move to the surviving merchant; the combined distribution will be:
                </p>
                <ul className="text-sm space-y-1 mb-4 bg-black/5 rounded p-2.5">
                  {mergeSource && [...mergeTarget.distribution, ...mergeSource.distribution]
                    .reduce<{ category: string; count: number }[]>((acc, d) => {
                      const existing = acc.find((x) => x.category === d.category);
                      if (existing) existing.count += d.confirmationCount;
                      else acc.push({ category: d.category, count: d.confirmationCount });
                      return acc;
                    }, [])
                    .sort((a, b) => b.count - a.count)
                    .map((d) => (
                      <li key={d.category} className="flex justify-between">
                        <span>{d.category}</span>
                        <span className="text-gray-500">{d.count} confirmations</span>
                      </li>
                    ))}
                  {mergeSource && mergeTarget.distribution.length === 0 && mergeSource.distribution.length === 0 && (
                    <li className="text-gray-400 italic">No learning history on either merchant yet.</li>
                  )}
                </ul>
                <div className="flex justify-end gap-2">
                  <button onClick={() => setMergeStep('select')} className="px-3 py-1.5 text-sm rounded border">Back</button>
                  <button
                    disabled={merging}
                    onClick={confirmMerge}
                    className="px-3 py-1.5 text-sm rounded bg-danger text-white disabled:opacity-50"
                  >
                    {merging ? 'Merging…' : 'Confirm Merge'}
                  </button>
                </div>
              </>
            )}
          </div>
        </div>
      )}

      {/* Audit history panel -- spec §5.2/§6.4: view history, undo the most recent change with
          a confirmation showing exactly what will be reverted. */}
      {auditFor && (
        <div className="fixed inset-0 bg-black/40 flex justify-end z-30" onClick={() => setAuditFor(null)}>
          <div className="bg-card shadow-xl h-full w-[380px] p-5 overflow-y-auto" onClick={(e) => e.stopPropagation()}>
            <div className="flex justify-between items-center mb-4">
              <h2 className="font-semibold">{auditFor.canonicalName} — History</h2>
              <button onClick={() => setAuditFor(null)}><X size={16} /></button>
            </div>

            {auditLoading ? (
              <p className="text-sm text-gray-500">Loading…</p>
            ) : auditEntries.length === 0 ? (
              <p className="text-sm italic text-gray-500">No learning history yet.</p>
            ) : (
              <ul className="space-y-3">
                {auditEntries.map((a, i) => (
                  <li key={i} className="text-sm border-l-2 border-black/10 pl-3">
                    <div className="font-medium">{a.action}</div>
                    {a.previousCategory && a.newCategory && a.action === 'CORRECTED' && (
                      <div className="text-gray-500">{a.previousCategory} → {a.newCategory}</div>
                    )}
                    {a.newCategory && a.action === 'LEARNED' && (
                      <div className="text-gray-500">Confirmed as {a.newCategory}</div>
                    )}
                    {a.action === 'UNDONE' && a.previousCategory && (
                      <div className="text-gray-500">Reverted {a.previousCategory}</div>
                    )}
                    {a.action === 'MERGED' && <div className="text-gray-500">Merchants merged</div>}
                    <div className="text-xs text-gray-400">{new Date(a.createdAt).toLocaleString()}</div>
                  </li>
                ))}
              </ul>
            )}

            {canUndo && (
              <button
                disabled={undoing}
                onClick={undoLast}
                className="mt-5 w-full border border-danger text-danger text-sm rounded py-2 disabled:opacity-50"
              >
                {undoing ? 'Undoing…' : `Undo: ${auditEntries[0].action}`}
              </button>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
