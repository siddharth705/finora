import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ArrowDownCircle, ArrowUpCircle, PiggyBank, PieChart, UploadCloud } from 'lucide-react';
import { reportsApi, type ReportData } from '../api/endpoints';
import { useAsyncGuard } from '../hooks/useAsyncGuard';
import { downloadBlob, toCsv } from '../lib/download';
import { FinoraCard, MetricCard, EmptyState, SectionHeader } from '../design-system';

function fmt(n: number) {
  // Negative amounts (e.g. a month where spend exceeded income) must render as "-₹500",
  // not "₹-500" -- string concatenation put the currency symbol before the sign.
  return (n < 0 ? '-₹' : '₹') + Math.round(Math.abs(n)).toLocaleString('en-IN');
}

function downloadCsv(report: ReportData) {
  // Category names are user-controlled and end up in the first column, so cells are escaped
  // against spreadsheet formula interpretation as well as against CSV parsing -- see csvCell.
  // The download itself goes through downloadBlob so the object URL isn't revoked out from under
  // the browser's own read. Both used to be open-coded here and in endpoints.ts, identically
  // wrong in both places.
  const csv = toCsv([['Category', 'Amount'], ...report.categories.map((c) => [c.category, c.amount])]);
  downloadBlob(new Blob([csv], { type: 'text/csv' }), `fynora-report-${report.month}.csv`);
}

export default function Reports() {
  const [months, setMonths] = useState<string[]>([]);
  const [month, setMonth] = useState<string>('');
  const [report, setReport] = useState<ReportData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const { beginRequest } = useAsyncGuard();

  useEffect(() => {
    reportsApi.availableMonths().then((m) => {
      setMonths(m);
      if (m.length > 0) setMonth(m[m.length - 1]);
      setLoading(false);
    }).catch(() => {
      setError(true);
      setLoading(false);
    });
  }, []);

  useEffect(() => {
    if (!month) return;
    // Guards against a slower response for a month the user already navigated away from
    // overwriting the report for the month currently on screen.
    const isCurrent = beginRequest();
    reportsApi.forMonth(month).then((r) => {
      if (isCurrent()) setReport(r);
    }).catch(() => {
      if (isCurrent()) setError(true);
    });
  }, [month, beginRequest]);

  if (loading) return <p className="text-muted">Loading…</p>;

  if (error) return <p className="text-muted">Couldn't load reports — please try again later.</p>;

  if (months.length === 0) {
    return (
      <FinoraCard padding="lg" className="max-w-md mx-auto my-12">
        <EmptyState
          icon={PieChart}
          iconBg="bg-purple-100"
          iconColor="text-purple-600"
          title="No reports yet"
          desc="Add transactions in the Ledger or import a statement to see your monthly reports."
          cta={
            <Link to="/app/import" className="inline-flex items-center gap-1.5 bg-primary text-on-primary hover:bg-primary-dark rounded-lg px-4 py-2 text-xs font-semibold">
              <UploadCloud size={14} /> Import Statement
            </Link>
          }
        />
      </FinoraCard>
    );
  }

  return (
    <div className="space-y-6">
      <FinoraCard padding="sm" className="flex flex-wrap items-end gap-3 justify-between">
        <div>
          <label htmlFor="reports-month" className="block text-xs uppercase text-gray-500 mb-1">Month</label>
          <select id="reports-month" value={month} onChange={(e) => setMonth(e.target.value)} className="bg-card text-ink border rounded px-2 py-1.5 text-sm">
            {months.map((m) => <option key={m} value={m}>{m}</option>)}
          </select>
        </div>
        <div className="flex gap-2">
          <button onClick={() => report && downloadCsv(report)} className="border border-border rounded px-4 py-2 text-xs uppercase">
            Export CSV
          </button>
          <button onClick={() => window.print()} className="bg-primary text-on-primary hover:bg-primary-dark rounded px-4 py-2 text-xs uppercase">
            Print / PDF
          </button>
        </div>
      </FinoraCard>

      {report && (
        <>
          <div className="grid grid-cols-3 gap-4">
            <MetricCard label="Income" value={fmt(report.income)} icon={ArrowDownCircle} iconBg="bg-green-100" iconColor="text-green-600" valueColor="text-success" />
            <MetricCard label="Expense" value={fmt(report.expense)} icon={ArrowUpCircle} iconBg="bg-red-100" iconColor="text-red-600" valueColor="text-danger" />
            <MetricCard label="Net" value={fmt(report.income - report.expense)} icon={PiggyBank} iconBg="bg-primary-light" iconColor="text-primary" />
          </div>

          <FinoraCard>
            <SectionHeader title="Category Breakdown" />
            {report.categories.length === 0 ? (
              <EmptyState
                icon={PieChart}
                iconBg="bg-purple-100"
                iconColor="text-purple-600"
                title="No expenses recorded"
                desc="Nothing was spent this month in any category."
              />
            ) : (
              <div className="space-y-2">
                {report.categories.map((c) => {
                  const pct = report.expense > 0 ? (c.amount / report.expense) * 100 : 0;
                  return (
                    <div key={c.category} className="grid grid-cols-[120px_1fr_90px] items-center gap-3 text-sm">
                      <span>{c.category}</span>
                      <div className="h-2 bg-black/10 rounded overflow-hidden">
                        <div className="h-full bg-primary" style={{ width: `${pct}%` }} />
                      </div>
                      <span className="text-right">{fmt(c.amount)}</span>
                    </div>
                  );
                })}
              </div>
            )}
          </FinoraCard>
        </>
      )}
    </div>
  );
}
