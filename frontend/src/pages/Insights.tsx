import { useEffect, useState } from 'react';
import { Repeat, TrendingUp } from 'lucide-react';
import { insightsApi, recurringApi, type InsightsData, type RecurringItem } from '../api/endpoints';
import { FinoraCard, EmptyState, SectionHeader } from '../design-system';

function fmt(n: number) {
  // Negative amounts (e.g. a month where spend exceeded income) must render as "-₹500",
  // not "₹-500" -- string concatenation put the currency symbol before the sign.
  return (n < 0 ? '-₹' : '₹') + Math.round(Math.abs(n)).toLocaleString('en-IN');
}

export default function Insights() {
  const [data, setData] = useState<InsightsData | null>(null);
  const [recurring, setRecurring] = useState<RecurringItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);

  useEffect(() => {
    Promise.all([insightsApi.get(), recurringApi.list()])
      .then(([insights, rec]) => { setData(insights); setRecurring(rec); })
      .catch(() => setError(true))
      .finally(() => setLoading(false));
  }, []);

  if (loading) return <p className="text-muted">Loading…</p>;
  // Bug fix: `data` staying null on failure used to fall through to `return null`, rendering a
  // blank page with no indication anything went wrong -- same fix as Dashboard.tsx, matching
  // Reports.tsx's existing "Couldn't load ... — please try again later" convention.
  if (error || !data) return <p className="text-muted">Couldn't load your insights — please try again later.</p>;

  const movers = data.movers.filter((m) => m.pctChange !== null).slice(0, 6);

  return (
    <div className="space-y-6">
      <div className="bg-primary/10 border-l-4 border-primary rounded p-3 text-sm">
        These are rule-based statistical observations from your real transaction history — not an LLM-generated assistant (that's a later milestone; see the roadmap's AI section).
      </div>

      <FinoraCard>
        <SectionHeader title="This Month's Observations" />
        <div className="space-y-3">
          {data.sentences.map((s, i) => (
            <p key={i} className="text-sm leading-relaxed border-l-4 border-border bg-black/[0.02] rounded p-3">{s}</p>
          ))}
        </div>
      </FinoraCard>

      <FinoraCard>
        <SectionHeader title="Recurring Payments & Subscriptions" />
        {recurring.length === 0 ? (
          <EmptyState
            icon={Repeat}
            iconBg="bg-primary-light"
            iconColor="text-primary"
            title="No recurring payments detected yet"
            desc="This needs at least 2 charges from the same merchant with a regular interval to spot a pattern."
          />
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
      </FinoraCard>

      <FinoraCard>
        <SectionHeader title="Category Movers vs. Recent Average" />
        {movers.length === 0 ? (
          <EmptyState
            icon={TrendingUp}
            iconBg="bg-purple-100"
            iconColor="text-purple-600"
            title="Not enough history yet"
            desc="Add a few months of transactions to compare trends."
          />
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
      </FinoraCard>
    </div>
  );
}
