import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Search, ListOrdered } from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { adminImportRowTraceApi } from '../api/endpoints';
import type { ImportRowTrace as ImportRowTraceType } from '../types';

/**
 * One import, row by row -- Founder Operations Dashboard, Import Row Trace (docs/proposals/
 * reconciliation-evolution-roadmap-proposal.md Part 9), scoped to successfully-imported rows
 * only: each row's original position in the file, next to the transaction it became.
 *
 * <h2>Scoped down from the roadmap's original description</h2>
 * The roadmap's "Import Explorer" also describes surfacing exactly where a DROPPED row went and
 * why. Giving that per-row visibility would require a narrow exception to a privacy policy this
 * codebase enforces consistently elsewhere (see {@code DocumentContext.recordUnparseable} /
 * {@code UnparseableRowSummary}: a dropped row's position and content are deliberately collapsed
 * into an aggregate histogram, never kept per-row) -- a policy call, not an engineering one. This
 * page traces only rows that became real transactions, whose content is already fully persisted
 * regardless, so it needs no such exception. Dropped/excluded rows stay aggregate-only, same as
 * they are on {@code ImportTrace} today.
 *
 * <h2>No position data is an explicit answer</h2>
 * An import from before this field existed, or confirmed by a client that predates echoing it,
 * returns an empty row list -- stated as "no position data available", not a blank table.
 */

function rupees(n: number): string {
  return `₹${n.toLocaleString('en-IN')}`;
}

function timestamp(iso: string): string {
  return new Date(iso).toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' });
}

function TraceView({ trace }: { trace: ImportRowTraceType }) {
  if (trace.rows.length === 0) {
    return (
      <div className="bg-card border border-border rounded-xl2 p-8 text-center">
        <ListOrdered size={22} className="text-muted mx-auto mb-2" />
        <p className="text-sm text-muted">
          No position data available for this import -- it predates row-position tracking, or was
          confirmed by a client that predates echoing it.
        </p>
      </div>
    );
  }

  return (
    <section className="bg-card border border-border rounded-xl2 p-5 mb-4">
      <div className="flex items-center gap-2 mb-1">
        <ListOrdered size={16} className="text-primary" />
        <h2 className="font-semibold text-ink text-sm">Rows</h2>
      </div>
      <p className="text-xs text-muted mb-3">
        Every row this import confirmed into a transaction, by its original position in the file.
        A dropped or excluded row is not listed here -- see the Import Trace's verification panel
        for aggregate counts.
      </p>
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-[10px] uppercase text-muted border-b border-border">
              <th className="py-2 pr-3">Row</th><th className="py-2 pr-3">Transaction</th>
              <th className="py-2 pr-3">Description</th><th className="py-2 pr-3">Date</th>
              <th className="py-2">Amount</th>
            </tr>
          </thead>
          <tbody>
            {trace.rows.map((r) => (
              <tr key={r.transactionId} className="border-b border-border last:border-0 align-top">
                <td className="py-2 pr-3 text-ink font-mono">{r.rowPosition}</td>
                <td className="py-2 pr-3 text-muted font-mono text-xs break-all">{r.transactionId}</td>
                <td className="py-2 pr-3 text-ink">{r.description ?? '—'}</td>
                <td className="py-2 pr-3 text-muted">{timestamp(r.txnDate)}</td>
                <td className="py-2 text-ink">{rupees(r.amount)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

export default function ImportRowTracePage() {
  const [input, setInput] = useState('');
  const [submitted, setSubmitted] = useState<string | null>(null);

  const { data: trace, isFetching, error } = useQuery({
    queryKey: ['import-row-trace', submitted],
    queryFn: () => adminImportRowTraceApi.trace(submitted!),
    enabled: submitted !== null,
    retry: false,
  });

  return (
    <AdminLayout
      title="Import Row Trace"
      subtitle="One import, row by row -- each successfully-imported row next to its original position in the file."
    >
      <RequirePermission permission="PLATFORM_DIAGNOSTICS_VIEW">
        <form
          className="bg-card border border-border rounded-xl2 p-5 mb-4"
          onSubmit={(e) => {
            e.preventDefault();
            const value = input.trim();
            if (value) setSubmitted(value);
          }}
        >
          <div className="flex flex-wrap items-end gap-3">
            <div className="flex-1 min-w-[240px]">
              <label htmlFor="statement-import-id" className="block text-[10px] uppercase tracking-wide text-muted mb-1">
                Statement import id
              </label>
              <input
                id="statement-import-id"
                value={input}
                onChange={(e) => setInput(e.target.value)}
                placeholder="0f8b1c2d-…"
                className="w-full bg-bg border border-border rounded-lg px-3 py-2 text-sm text-ink font-mono"
              />
            </div>
            <button
              type="submit"
              disabled={!input.trim() || isFetching}
              className="flex items-center gap-1.5 bg-primary text-on-primary rounded-lg px-4 py-2 text-sm disabled:opacity-50"
            >
              <Search size={15} />
              {isFetching ? 'Looking…' : 'Trace'}
            </button>
          </div>
        </form>

        {error && (
          <div className="bg-card border border-border rounded-xl2 p-5 mb-4">
            <p className="text-sm text-danger">
              No statement import with that id. Check it against the Import Trace page.
            </p>
          </div>
        )}

        {trace && <TraceView trace={trace} />}

        {!submitted && !trace && (
          <div className="bg-card border border-border rounded-xl2 p-8 text-center">
            <ListOrdered size={22} className="text-muted mx-auto mb-2" />
            <p className="text-sm text-muted">
              Enter a statement import id above to trace its rows back to their original positions.
            </p>
          </div>
        )}
      </RequirePermission>
    </AdminLayout>
  );
}
