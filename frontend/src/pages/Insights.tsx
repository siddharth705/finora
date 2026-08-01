import { useEffect, useState } from 'react';
import { insightsApi, recurringApi, type InsightsData, type RecurringItem } from '../api/endpoints';

function fmt(n: number) {
  // Negative amounts (e.g. a month where spend exceeded income) must render as "-₹500",
  // not "₹-500" -- string concatenation put the currency symbol before the sign.
  return (n < 0 ? '-₹' : '₹') + Math.round(Math.abs(n)).toLocaleString('en-IN');
}

export default function Insights() {
  const [data, setData] = useState<InsightsData | null>(null);
  const [recurring, setRecurring] = useState<RecurringItem[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    Promise.all([insightsApi.get(), recurringApi.list()])
      .then(([insights, rec]) => { setData(insights); setRecurring(rec); })
      // `data` stays null on failure, which the existing `if (!data) return null` below already
      // renders as gracefully as this page currently distinguishes "still loading" from "failed".
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  if (loading) return <p className="text-muted">Loading…</p>;
  if (!data) return null;

  const movers = data.movers.filter((m) => m.pctChange !== null).slice(0, 6);

  return (
    <div className="space-y-6">
      <div className="bg-primary/10 border-l-4 border-primary rounded p-3 text-sm">
        These are rule-based statistical observations from your real transaction history — not an LLM-generated assistant (that's a later milestone; see the roadmap's AI section).
      </div>

      <div className="bg-card rounded shadow p-5">
        <p className="text-xs uppercase text-gray-500 mb-3">This Month's Observations</p>
        <div className="space-y-3">
          {data.sentences.map((s, i) => (
            <p key={i} className="text-sm leading-relaxed border-l-4 border-border bg-black/[0.02] rounded p-3">{s}</p>
          ))}
        </div>
      </div>

      <div className="bg-card rounded shadow p-5">
        <p className="text-xs uppercase text-gray-500 mb-3">Recurring Payments & Subscriptions</p>
        {recurring.length === 0 ? (
          <p className="text-sm italic text-gray-500">No recurring payments detected yet — this needs at least 2 charges from the same merchant with a regular interval to spot a pattern.</p>
        ) : (
          <div className="space-y-2">
            {recurring.map((r) => (
              <div key={r.merchant} className="flex justify-between items-center text-sm border-b border-dashed py-2">
                <span className="capitalize">{r.merchant} <span className="text-[10px] uppercase bg-primary/10 text-primary px-1.5 py-0.5 rounded ml-1">{r.label}</span></span>
                <span className="flex items-center gap-3 text-xs text-gray-500">
                  <span>{fmt(r.averageAmount)} · {r.occurrences}x seen</span>
                  <span>next ~{r.nextEstimate}</span>
                </span>
              </div>
            ))}
          </div>
        )}
      </div>

      <div className="bg-card rounded shadow p-5">
        <p className="text-xs uppercase text-gray-500 mb-3">Category Movers vs. Recent Average</p>
        {movers.length === 0 ? (
          <p className="text-sm italic text-gray-500">Not enough history yet to compare trends — add a few months of transactions.</p>
        ) : (
          <div className="space-y-2">
            {movers.map((m) => (
              <div key={m.category} className="flex justify-between items-center text-sm border-b border-dashed py-2">
                <span>{m.category}</span>
                <span className="flex items-center gap-3">
                  <span className="text-gray-400 text-xs">{fmt(m.current)} vs usual {fmt(m.priorAverage)}</span>
                  <span className={m.pctChange! >= 0 ? 'text-danger' : 'text-success'}>
                    {m.pctChange! >= 0 ? '▲' : '▼'} {Math.abs(m.pctChange!).toFixed(0)}%
                  </span>
                </span>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
