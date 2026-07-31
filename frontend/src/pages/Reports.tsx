import { useEffect, useState } from 'react';
import { reportsApi, type ReportData } from '../api/endpoints';

function fmt(n: number) {
  // Negative amounts (e.g. a month where spend exceeded income) must render as "-₹500",
  // not "₹-500" -- string concatenation put the currency symbol before the sign.
  return (n < 0 ? '-₹' : '₹') + Math.round(Math.abs(n)).toLocaleString('en-IN');
}

function downloadCsv(report: ReportData) {
  const rows = [['Category', 'Amount'], ...report.categories.map((c) => [c.category, String(c.amount)])];
  const csv = rows.map((r) => r.map((v) => `"${v.replace(/"/g, '""')}"`).join(',')).join('\n');
  const blob = new Blob([csv], { type: 'text/csv' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `finora-report-${report.month}.csv`;
  a.click();
  // Without this, each export leaks the blob's object URL for the lifetime of the page --
  // small per-click, but it never gets reclaimed on a long-lived SPA session. endpoints.ts's
  // statementImportsApi.downloadFile already follows this revoke pattern; this brings
  // Reports.tsx's newer download path in line with it.
  URL.revokeObjectURL(url);
}

export default function Reports() {
  const [months, setMonths] = useState<string[]>([]);
  const [month, setMonth] = useState<string>('');
  const [report, setReport] = useState<ReportData | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    reportsApi.availableMonths().then((m) => {
      setMonths(m);
      if (m.length > 0) setMonth(m[m.length - 1]);
      setLoading(false);
    });
  }, []);

  useEffect(() => {
    if (!month) return;
    reportsApi.forMonth(month).then(setReport);
  }, [month]);

  if (loading) return <p className="text-muted">Loading…</p>;

  if (months.length === 0) {
    return <p className="text-muted">No transactions yet — add some in the Ledger or Import a statement to see reports.</p>;
  }

  return (
    <div className="space-y-6">
      <div className="bg-card rounded p-4 shadow flex flex-wrap items-end gap-3 justify-between">
        <div>
          <label className="block text-xs uppercase text-gray-500 mb-1">Month</label>
          <select value={month} onChange={(e) => setMonth(e.target.value)} className="border rounded px-2 py-1.5 text-sm">
            {months.map((m) => <option key={m} value={m}>{m}</option>)}
          </select>
        </div>
        <div className="flex gap-2">
          <button onClick={() => report && downloadCsv(report)} className="border border-border rounded px-4 py-2 text-xs uppercase">
            Export CSV
          </button>
          <button onClick={() => window.print()} className="bg-primary text-white hover:bg-primary-dark rounded px-4 py-2 text-xs uppercase">
            Print / PDF
          </button>
        </div>
      </div>

      {report && (
        <>
          <div className="grid grid-cols-3 gap-4">
            <div className="bg-card rounded p-4 shadow border-l-4 border-success">
              <p className="text-[10px] uppercase text-gray-500 mb-1">Income</p>
              <p className="text-xl font-semibold text-success">{fmt(report.income)}</p>
            </div>
            <div className="bg-card rounded p-4 shadow border-l-4 border-danger">
              <p className="text-[10px] uppercase text-gray-500 mb-1">Expense</p>
              <p className="text-xl font-semibold text-danger">{fmt(report.expense)}</p>
            </div>
            <div className="bg-card rounded p-4 shadow border-l-4 border-primary">
              <p className="text-[10px] uppercase text-gray-500 mb-1">Net</p>
              <p className="text-xl font-semibold">{fmt(report.income - report.expense)}</p>
            </div>
          </div>

          <div className="bg-card rounded shadow p-5">
            <p className="text-xs uppercase text-gray-500 mb-3">Category Breakdown</p>
            {report.categories.length === 0 ? (
              <p className="text-sm italic text-gray-500">No expenses recorded this month.</p>
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
          </div>
        </>
      )}
    </div>
  );
}
