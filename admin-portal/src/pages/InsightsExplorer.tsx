import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Search, CalendarClock, Wallet, Tags, Store } from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { adminInsightsExplorerApi } from '../api/endpoints';
import type { InsightsExplorerTracedTransaction, InsightsExplorerTrace } from '../types';

/**
 * One user's dashboard insights, traced back to the transaction set and formula that produced
 * each number -- Phase 2's Founder Operations Dashboard, Insight Explorer (docs/proposals/
 * reconciliation-evolution-roadmap-proposal.md, Part 9): "since InsightsService computes
 * everything on the fly with no persistence, this explorer's job is to re-run that computation in
 * a debug mode that logs its inputs instead of just returning the final number."
 *
 * <h2>Re-run, not read back</h2>
 * Unlike the Reconciliation Explorer, there is no persisted row to look up -- every number here is
 * InsightsService's own formula recomputed against the same reportable transaction set, with the
 * per-transaction contributions kept instead of thrown away. The same position ImportTrace and the
 * Reconciliation Explorer both take: each panel reports what its own computation produced, no
 * derived verdict.
 *
 * <h2>No transaction data is an answer</h2>
 * A user with no reportable expenses gets an explicit "no spending data" panel, not a blank or
 * zeroed one -- the same state the user-facing dashboard answers with its own "upload or add
 * transactions" sentence.
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

function Field({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div>
      <p className="text-muted text-[10px] uppercase tracking-wide">{label}</p>
      <p className="text-ink text-sm">{value}</p>
    </div>
  );
}

function rupees(n: number): string {
  return `₹${n.toLocaleString('en-IN')}`;
}

function timestamp(iso: string): string {
  return new Date(iso).toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' });
}

function TransactionsTable({ transactions }: { transactions: InsightsExplorerTracedTransaction[] }) {
  return (
    <div className="overflow-x-auto mt-3">
      <table className="w-full text-sm">
        <thead>
          <tr className="text-left text-[10px] uppercase text-muted border-b border-border">
            <th className="py-2 pr-3">Transaction</th><th className="py-2 pr-3">Description</th>
            <th className="py-2 pr-3">Date</th><th className="py-2 pr-3">Raw amount</th>
            <th className="py-2">Reportable amount</th>
          </tr>
        </thead>
        <tbody>
          {transactions.map((t) => (
            <tr key={t.transactionId} className="border-b border-border last:border-0 align-top">
              <td className="py-2 pr-3 text-muted font-mono text-xs break-all">{t.transactionId}</td>
              <td className="py-2 pr-3 text-ink">{t.description ?? '—'}</td>
              <td className="py-2 pr-3 text-muted">{timestamp(t.txnDate)}</td>
              <td className="py-2 pr-3 text-muted">{rupees(t.rawAmount)}</td>
              <td className="py-2 text-ink">
                {rupees(t.reportableAmount)}
                {t.reportableAmount !== t.rawAmount && (
                  <span className="text-warning text-xs"> — refund netted</span>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function TraceView({ trace }: { trace: InsightsExplorerTrace }) {
  if (trace.reportingMonth === null) {
    return (
      <div className="bg-card border border-border rounded-xl2 p-8 text-center">
        <Wallet size={22} className="text-muted mx-auto mb-2" />
        <p className="text-sm text-muted">
          No spending data. This user has no reportable expense transactions -- the same state the
          dashboard answers with "upload or add transactions to see spending insights."
        </p>
      </div>
    );
  }

  return (
    <>
      <Panel
        icon={<CalendarClock size={16} className="text-primary" />}
        title="Reporting period"
        hint="The newest month this user has expense data for -- not necessarily the calendar's current month."
      >
        <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
          <Field label="Month" value={trace.reportingMonth} />
          <Field label="Is current calendar month" value={trace.reportingMonthIsCurrent ? 'Yes' : 'No'} />
        </div>
      </Panel>

      {trace.totalSpend && (
        <Panel
          icon={<Wallet size={16} className="text-primary" />}
          title="Total spend"
          hint="Every reportable expense in the reporting month, summed -- refunds already netted off."
        >
          <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mb-1">
            <Field label="Total" value={rupees(trace.totalSpend.amount)} />
            <Field label="Categories" value={trace.totalSpend.categoryCount} />
            <Field label="Transactions" value={trace.totalSpend.transactions.length} />
          </div>
          <TransactionsTable transactions={trace.totalSpend.transactions} />
        </Panel>
      )}

      {trace.topCategory && (
        <Panel
          icon={<Tags size={16} className="text-primary" />}
          title="Top category"
          hint="The category dashboard Insights names as this user's biggest, with the transactions that made it."
        >
          <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mb-1">
            <Field label="Category" value={trace.topCategory.category} />
            <Field label="Amount" value={rupees(trace.topCategory.amount)} />
          </div>
          <TransactionsTable transactions={trace.topCategory.transactions} />
        </Panel>
      )}

      {trace.topMerchant && (
        <Panel
          icon={<Store size={16} className="text-primary" />}
          title="Top merchant"
          hint='Falls back to description, then "Unknown", when a transaction has no merchant set -- same as the dashboard sentence.'
        >
          <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mb-1">
            <Field label="Merchant" value={trace.topMerchant.merchant} />
            <Field label="Amount" value={rupees(trace.topMerchant.amount)} />
          </div>
          <TransactionsTable transactions={trace.topMerchant.transactions} />
        </Panel>
      )}
    </>
  );
}

export default function InsightsExplorerPage() {
  const [input, setInput] = useState('');
  const [submitted, setSubmitted] = useState<string | null>(null);

  const { data: trace, isFetching, error } = useQuery({
    queryKey: ['insights-explorer', submitted],
    queryFn: () => adminInsightsExplorerApi.trace(submitted!),
    enabled: submitted !== null,
    retry: false,
  });

  return (
    <AdminLayout
      title="Insight Explorer"
      subtitle="One user's dashboard numbers, traced back to the transactions and formula that produced them."
    >
      <RequirePermission permission="INSIGHTS_EXPLORER_VIEW">
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
              <label htmlFor="user-id" className="block text-[10px] uppercase tracking-wide text-muted mb-1">
                User id
              </label>
              <input
                id="user-id"
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
              No user with that id. Check it against the Users page.
            </p>
          </div>
        )}

        {trace && <TraceView trace={trace} />}

        {!submitted && !trace && (
          <div className="bg-card border border-border rounded-xl2 p-8 text-center">
            <Wallet size={22} className="text-muted mx-auto mb-2" />
            <p className="text-sm text-muted">
              Enter a user id above to trace their dashboard numbers back to the transactions that produced them.
            </p>
          </div>
        )}
      </RequirePermission>
    </AdminLayout>
  );
}
