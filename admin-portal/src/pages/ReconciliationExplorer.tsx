import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Search, FileText, Tags, GitMerge, Gauge, CheckCircle2 } from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { adminReconciliationExplorerApi } from '../api/endpoints';
import type { ReconciliationExplorerEdge, ReconciliationExplorerTrace } from '../types';

/**
 * One transaction, traced from raw to final classification -- Phase 2's Founder Operations
 * Dashboard, Reconciliation Explorer (docs/proposals/reconciliation-evolution-roadmap-
 * proposal.md, Part 9): "Raw -> Normalized -> Matched -> Confidence -> Final classification, for
 * any transaction."
 *
 * <h2>Assembled, not scored</h2>
 * No overall verdict, no health badge -- the same position `ImportTrace` and
 * `VerificationReport` both take: each panel reports what its own table recorded, and the
 * operator draws the conclusion. A confidence number sits beside the classification it produced,
 * it never becomes one.
 *
 * <h2>An absent panel is an answer</h2>
 * No matched edges means this transaction was never linked into the graph -- either it is
 * ordinary OK activity, or it predates PR #460's dual-write and only carries a legacy-column
 * verdict. Both read as "no edges" here; the classification panel below is what still answers
 * "was this reconciled at all."
 */

function Panel({
  icon, title, hint, empty, children,
}: {
  icon: React.ReactNode;
  title: string;
  hint?: string;
  empty?: string | null;
  children?: React.ReactNode;
}) {
  return (
    <section className="bg-card border border-border rounded-xl2 p-5 mb-4">
      <div className="flex items-center gap-2 mb-1">
        {icon}
        <h2 className="font-semibold text-ink text-sm">{title}</h2>
      </div>
      {hint && <p className="text-xs text-muted mb-3">{hint}</p>}
      {empty ? <p className="text-sm text-muted italic">{empty}</p> : children}
    </section>
  );
}

function Field({ label, value, mono }: { label: string; value: React.ReactNode; mono?: boolean }) {
  return (
    <div>
      <p className="text-muted text-[10px] uppercase tracking-wide">{label}</p>
      <p className={`text-ink text-sm ${mono ? 'font-mono text-xs break-all' : ''}`}>{value}</p>
    </div>
  );
}

function timestamp(iso: string): string {
  return new Date(iso).toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' });
}

const STATUS_TONE: Record<ReconciliationExplorerEdge['status'], string> = {
  AUTO_CONFIRMED: 'text-success',
  USER_CONFIRMED: 'text-success',
  CANDIDATE: 'text-warning',
  REJECTED: 'text-danger',
};

function EdgeRow({ edge }: { edge: ReconciliationExplorerEdge }) {
  return (
    <tr className="border-b border-border last:border-0 align-top">
      <td className="py-2 pr-3 text-ink">{edge.relationshipType}</td>
      <td className="py-2 pr-3 text-muted font-mono text-xs break-all">{edge.counterpartTransactionId}</td>
      <td className="py-2 pr-3 text-ink">{edge.confidence === null ? 'Not scored' : `${edge.confidence}`}</td>
      <td className="py-2 pr-3 text-muted">{edge.sourceTrust === null ? '—' : `${edge.sourceTrust}`}</td>
      <td className={`py-2 pr-3 font-medium ${STATUS_TONE[edge.status]}`}>
        {edge.status}
        {/* CANDIDATE is the needs-review signal ConfidenceScorer's threshold produces -- the
            one reading an operator would otherwise have to work out by comparing the confidence
            column against a threshold they'd have to know. */}
        {edge.status === 'CANDIDATE' && <span className="text-warning text-xs"> — needs review</span>}
      </td>
      <td className="py-2 text-muted">{edge.detectionMethod}</td>
    </tr>
  );
}

function TraceView({ trace }: { trace: ReconciliationExplorerTrace }) {
  return (
    <>
      <Panel
        icon={<FileText size={16} className="text-primary" />}
        title="Raw"
        hint="The transaction exactly as parsed or entered, before any reconciliation or categorization."
      >
        <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
          <Field label="Description" value={trace.raw.description ?? '—'} />
          <Field label="Amount" value={`₹${trace.raw.amount.toLocaleString('en-IN')}`} />
          <Field label="Type" value={trace.raw.txnType} />
          <Field label="Date" value={timestamp(trace.raw.txnDate)} />
          <Field label="Source" value={trace.raw.source} />
        </div>
      </Panel>

      <Panel
        icon={<Tags size={16} className="text-primary" />}
        title="Normalized"
        hint="Merchant resolves straight off the row; category is the one lookup this step performs."
      >
        <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
          <Field label="Merchant" value={trace.normalized.merchant ?? 'Not resolved'} />
          <Field label="Category" value={trace.normalized.categoryName ?? 'Uncategorized'} />
        </div>
      </Panel>

      <Panel
        icon={<GitMerge size={16} className="text-primary" />}
        title="Matched"
        hint="Every graph edge touching this transaction directly (depth 1) -- not a multi-hop walk."
        empty={trace.edges.length > 0 ? null
          : 'No matched edges. Either this is ordinary unreconciled activity, or it predates the transaction graph and only carries a legacy classification.'}
      >
        {trace.edges.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-[10px] uppercase text-muted border-b border-border">
                  <th className="py-2 pr-3">Type</th><th className="py-2 pr-3">Counterpart</th>
                  <th className="py-2 pr-3">Confidence</th><th className="py-2 pr-3">Source trust</th>
                  <th className="py-2 pr-3">Status</th><th className="py-2">Detection</th>
                </tr>
              </thead>
              <tbody>
                {trace.edges.map((e) => <EdgeRow key={e.edgeId} edge={e} />)}
              </tbody>
            </table>
          </div>
        )}
      </Panel>

      <Panel
        icon={<Gauge size={16} className="text-primary" />}
        title="Confidence"
        hint="How sure each match was, and how much the channel it came from is trusted in general -- two independent scores, never blended into one."
        empty={trace.edges.length > 0 ? null : 'Nothing to score without a matched edge.'}
      >
        {trace.edges.length > 0 && (
          <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
            {trace.edges.map((e) => (
              <Field
                key={e.edgeId}
                label={e.relationshipType}
                value={e.confidence === null ? 'Not scored' : `${e.confidence} / 100`}
              />
            ))}
          </div>
        )}
      </Panel>

      <Panel
        icon={<CheckCircle2 size={16} className="text-primary" />}
        title="Final classification"
        hint="The transaction's own verdict, and the one-shot explanation that produced it."
      >
        <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mb-3">
          <Field label="Reconciliation status" value={trace.classification.reconciliationStatus} />
        </div>
        {trace.classification.transactionExplanation ? (
          <p className="text-muted font-mono text-xs break-all">
            {Object.entries(trace.classification.transactionExplanation)
              .map(([k, v]) => `${k}=${typeof v === 'object' ? JSON.stringify(v) : String(v)}`)
              .join('  ')}
          </p>
        ) : (
          <p className="text-sm text-muted italic">
            No explanation recorded -- classified before this existed, or never matched.
          </p>
        )}
      </Panel>
    </>
  );
}

export default function ReconciliationExplorerPage() {
  const [input, setInput] = useState('');
  const [submitted, setSubmitted] = useState<string | null>(null);

  const { data: trace, isFetching, error } = useQuery({
    queryKey: ['reconciliation-explorer', submitted],
    queryFn: () => adminReconciliationExplorerApi.trace(submitted!),
    enabled: submitted !== null,
    retry: false,
  });

  return (
    <AdminLayout
      title="Reconciliation Explorer"
      subtitle="One transaction, from its raw shape through to its final reconciliation verdict."
    >
      <RequirePermission permission="RECONCILIATION_VIEW">
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
              <label htmlFor="transaction-id" className="block text-[10px] uppercase tracking-wide text-muted mb-1">
                Transaction id
              </label>
              <input
                id="transaction-id"
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
              No transaction with that id. Check it against the Ledger or Reconciliation Monitor.
            </p>
          </div>
        )}

        {trace && <TraceView trace={trace} />}

        {!submitted && !trace && (
          <div className="bg-card border border-border rounded-xl2 p-8 text-center">
            <GitMerge size={22} className="text-muted mx-auto mb-2" />
            <p className="text-sm text-muted">
              Enter a transaction id above to trace it from raw to final classification.
            </p>
          </div>
        )}
      </RequirePermission>
    </AdminLayout>
  );
}
