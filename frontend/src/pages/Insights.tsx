import { useEffect, useState } from 'react';
import { Repeat, TrendingUp } from 'lucide-react';
import { insightsApi, recurringApi, onboardingApi, type InsightsData, type RecurringItem, type ChecklistStatus } from '../api/endpoints';
import { FinoraCard, EmptyState, SectionHeader, Skeleton } from '../design-system';
import { useDelayedLoading } from '../hooks/useDelayedLoading';

function fmt(n: number) {
  // Negative amounts (e.g. a month where spend exceeded income) must render as "-₹500",
  // not "₹-500" -- string concatenation put the currency symbol before the sign.
  return (n < 0 ? '-₹' : '₹') + Math.round(Math.abs(n)).toLocaleString('en-IN');
}

/** Matches the observation blocks' real shape: full-width padded boxes, not text lines. */
function ObservationsSkeleton() {
  return (
    <div className="space-y-3">
      {[0, 1, 2].map((i) => (
        <Skeleton.Block key={i} className="h-12 w-full" />
      ))}
    </div>
  );
}

/**
 * The row shape both list cards share: a label on the left, figures on the right, on a dashed
 * divider -- matching the real `flex justify-between ... border-b border-dashed py-2` rows rather
 * than using Skeleton.Row, whose fixed field-and-button composition belongs to a different shape
 * (Ledger hand-composed its rows for the same reason).
 */
function ListSkeleton({ rows }: { rows: number }) {
  return (
    <div className="space-y-2">
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} className="flex justify-between items-center border-b border-dashed py-2">
          <Skeleton.Text width="w-1/3" />
          <Skeleton.Text width="w-1/4" className="h-2.5" />
        </div>
      ))}
    </div>
  );
}

export default function Insights() {
  const [data, setData] = useState<InsightsData | null>(null);
  const [recurring, setRecurring] = useState<RecurringItem[]>([]);
  // Two endpoints, two sets of flags. These used to share one `loading` and one `error` behind a
  // single Promise.all, which conflated sources that have no dependency on each other: /recurring
  // feeds only the Recurring card, /insights only the Observations and Movers cards. That shared
  // gate meant the whole page waited on the slower of the two, and -- worse -- a /recurring failure
  // blanked Observations and Movers even though /insights had succeeded. Splitting them is what
  // the roadmap's "section-scoped loading, not page-scoped, whenever sections are independently
  // sourced" rule requires (§1), not incidental refactoring.
  const [insightsLoading, setInsightsLoading] = useState(true);
  const [recurringLoading, setRecurringLoading] = useState(true);
  const [insightsError, setInsightsError] = useState(false);
  const [recurringError, setRecurringError] = useState(false);
  const [checklist, setChecklist] = useState<ChecklistStatus | null>(null);

  // Getting-started checklist: "View insights" fires once, on a 1.5s dwell rather than on mount
  // itself, so a user who opens this page and immediately navigates away doesn't get credited for
  // a screen they never actually looked at.
  useEffect(() => {
    onboardingApi.getChecklist().then(setChecklist).catch(() => {});
  }, []);
  useEffect(() => {
    const item = checklist?.items.find((i) => i.key === 'VIEW_INSIGHTS');
    if (!item || item.completed) return;
    const timer = setTimeout(() => {
      onboardingApi.completeChecklistItem('VIEW_INSIGHTS').catch(() => {});
    }, 1500);
    return () => clearTimeout(timer);
  }, [checklist]);

  useEffect(() => {
    insightsApi.get()
      .then(setData)
      .catch(() => setInsightsError(true))
      .finally(() => setInsightsLoading(false));
    recurringApi.list()
      .then(setRecurring)
      .catch(() => setRecurringError(true))
      .finally(() => setRecurringLoading(false));
  }, []);

  const showInsightsSkeleton = useDelayedLoading(insightsLoading);
  const showRecurringSkeleton = useDelayedLoading(recurringLoading);

  // `data` staying null on failure used to fall through to `return null`, rendering a blank page
  // with no indication anything went wrong. That message now lives per-card below rather than as a
  // page-level early return, so one failed endpoint no longer takes the other's card down with it.
  const insightsFailed = insightsError || !data;
  const movers = (data?.movers ?? []).filter((m) => m.pctChange !== null).slice(0, 6);

  return (
    <div className="space-y-6">
      <div className="bg-primary/10 border-l-4 border-primary rounded p-3 text-sm">
        These are rule-based statistical observations from your real transaction history — not an LLM-generated assistant (that's a later milestone; see the roadmap's AI section).
      </div>

      <FinoraCard>
        <SectionHeader title="This Month's Observations" />
        {insightsLoading ? (
          // Region outside the delayed gate, shapes inside -- the accessible label announces
          // immediately while only the visual shape waits out the anti-flash window
          // (ChartContainer.tsx is the reference implementation of this contract).
          <Skeleton.Region label="Loading this month's observations">
            {showInsightsSkeleton && <ObservationsSkeleton />}
          </Skeleton.Region>
        ) : insightsFailed ? (
          <p className="text-muted text-sm">Couldn't load your insights — please try again later.</p>
        ) : (
          <div className="space-y-3">
            {data!.sentences.map((s, i) => (
              <p key={i} className="text-sm leading-relaxed border-l-4 border-border bg-black/[0.02] rounded p-3">{s}</p>
            ))}
          </div>
        )}
      </FinoraCard>

      <FinoraCard>
        <SectionHeader title="Recurring Payments & Subscriptions" />
        {recurringLoading ? (
          <Skeleton.Region label="Loading recurring payments">
            {showRecurringSkeleton && <ListSkeleton rows={3} />}
          </Skeleton.Region>
        ) : recurringError ? (
          <p className="text-muted text-sm">Couldn't load your recurring payments — please try again later.</p>
        ) : recurring.length === 0 ? (
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
        {insightsLoading ? (
          <Skeleton.Region label="Loading category movers">
            {showInsightsSkeleton && <ListSkeleton rows={4} />}
          </Skeleton.Region>
        ) : insightsFailed ? (
          <p className="text-muted text-sm">Couldn't load your insights — please try again later.</p>
        ) : movers.length === 0 ? (
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
