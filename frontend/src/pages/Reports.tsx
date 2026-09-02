import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ArrowDownCircle, ArrowUpCircle, PiggyBank, PieChart, UploadCloud, Loader2 } from 'lucide-react';
import { reportsApi, type ReportData } from '../api/endpoints';
import { useAsyncGuard } from '../hooks/useAsyncGuard';
import { useDelayedLoading } from '../hooks/useDelayedLoading';
import { downloadBlob, toCsv } from '../lib/download';
import { Button, FinoraCard, MetricCard, EmptyState, SectionHeader, Skeleton } from '../design-system';

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

/** The month picker + Export/Print row, for the window before availableMonths() has resolved. */
function ToolbarSkeleton() {
  return (
    <FinoraCard padding="sm" className="flex flex-wrap items-end gap-3 justify-between">
      <div className="space-y-1">
        <Skeleton.Text width="w-12" className="h-2.5" />
        <Skeleton.Block className="h-8 w-32" />
      </div>
      <div className="flex gap-2">
        <Skeleton.Block className="h-8 w-24" />
        <Skeleton.Block className="h-8 w-24" />
      </div>
    </FinoraCard>
  );
}

/**
 * The KPI grid and Category Breakdown, shared by both loading windows (the months fetch and the
 * first forMonth fetch) so the page can't grow two subtly different skeletons for the same content.
 * Rows mirror the real `grid-cols-[120px_1fr_90px]` layout -- label, bar, amount -- so the switch
 * to real data doesn't reflow the column structure.
 */
function ReportBodySkeleton() {
  return (
    <>
      <div className="grid grid-cols-3 gap-4">
        {[0, 1, 2].map((i) => <Skeleton.Card key={i} />)}
      </div>
      <FinoraCard>
        <SectionHeader title="Category Breakdown" />
        <div className="space-y-2">
          {Array.from({ length: 5 }, (_, i) => (
            <div key={i} className="grid grid-cols-[120px_1fr_90px] items-center gap-3">
              <Skeleton.Text width="w-full" className="h-2.5" />
              <Skeleton.Block className="h-2 w-full" />
              <Skeleton.Text width="w-full" className="h-2.5" />
            </div>
          ))}
        </div>
      </FinoraCard>
    </>
  );
}

export default function Reports() {
  const [months, setMonths] = useState<string[]>([]);
  const [month, setMonth] = useState<string>('');
  const [report, setReport] = useState<ReportData | null>(null);
  const [loading, setLoading] = useState(true);
  // Separate from `loading`, which only ever covered availableMonths(). Without this there was no
  // signal at all for the forMonth() fetch: on first load the toolbar rendered with nothing beneath
  // it until the response landed, and on a month switch the PREVIOUS month's figures simply sat
  // there, silently, until they were replaced. Starts true because a report fetch always follows a
  // successful months fetch -- deferring it to the effect would leave one painted frame with no
  // report and nothing claiming to be loading one.
  const [reportLoading, setReportLoading] = useState(true);
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
    setReportLoading(true);
    reportsApi.forMonth(month).then((r) => {
      // Cleared through the SAME isCurrent() guard the report setter uses. Clearing it
      // unconditionally would let a slow response for an abandoned month switch the indicator off
      // while the month actually on screen is still in flight -- the race this page already solved
      // for its data, reintroduced for its loading state.
      if (isCurrent()) { setReport(r); setReportLoading(false); }
    }).catch(() => {
      if (isCurrent()) { setError(true); setReportLoading(false); }
    });
  }, [month, beginRequest]);

  const showMonthsSkeleton = useDelayedLoading(loading);
  // First load only -- `report === null` is what separates "nothing to show yet" from "showing last
  // month while the new one loads". Feeding the raw flag here would swap visible figures for a
  // skeleton on every dropdown change, which is precisely what useDelayedLoading's own doc comment
  // forbids; that case takes the Refreshing indicator below instead.
  const showReportSkeleton = useDelayedLoading(reportLoading && report === null);
  const refreshing = reportLoading && report !== null;

  if (loading) {
    return (
      <div className="space-y-6">
        <Skeleton.Region label="Loading your reports">
          {/* Spacing lives on this inner wrapper, not the Region: the Region's own sr-only label is
              its first child, so a `space-y-*` there would put a phantom top margin on the first
              visible shape and drop the skeleton ~24px below the content that replaces it. */}
          <div className="space-y-6">
            {showMonthsSkeleton && (
              <>
                <ToolbarSkeleton />
                <ReportBodySkeleton />
              </>
            )}
          </div>
        </Skeleton.Region>
      </div>
    );
  }

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
      {/* `relative` is new, and required: the Refreshing indicator below positions against it.
          Ledger's equivalent indicator works the same way (its container already carried it). */}
      <FinoraCard padding="sm" className="relative flex flex-wrap items-end gap-3 justify-between">
        {/* The month-switch signal. Deliberately an overlay on the toolbar rather than anything that
            displaces the figures below -- the whole point is that last month's numbers stay put and
            readable while the new ones load. Same treatment as Ledger's background-refetch row. */}
        {refreshing && (
          <div className="absolute top-2 right-3 text-[10px] uppercase text-primary flex items-center gap-1">
            <Loader2 size={11} className="animate-spin" aria-hidden="true" /> Refreshing…
          </div>
        )}
        <div>
          <label htmlFor="reports-month" className="block text-xs uppercase text-gray-500 mb-1">Month</label>
          <select id="reports-month" value={month} onChange={(e) => setMonth(e.target.value)} className="bg-card text-ink border rounded px-2 py-1.5 text-sm">
            {months.map((m) => <option key={m} value={m}>{m}</option>)}
          </select>
        </div>
        <div className="flex gap-2">
          {/* `uppercase` per call site: Button applies no text-transform of its own, and every
              button on this page had it. Same preservation the earlier phases used. */}
          {/* `refreshing` as well as `!report`: mid-switch `report` is deliberately still the OLD month,
              so an export fired in that window would silently write the month the user just left. */}
          <Button variant="secondary" onClick={() => report && downloadCsv(report)} disabled={!report || refreshing} className="uppercase">
            Export CSV
          </Button>
          <Button onClick={() => window.print()} className="uppercase">
            Print / PDF
          </Button>
        </div>
      </FinoraCard>

      {reportLoading && report === null ? (
        <Skeleton.Region label="Loading this month's report">
          <div className="space-y-6">
            {showReportSkeleton && <ReportBodySkeleton />}
          </div>
        </Skeleton.Region>
      ) : report && (
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
