import { useQuery } from '@tanstack/react-query';
import { PieChart } from 'lucide-react';
import { adminUserAnalyticsApi } from '../../api/endpoints';
import type { CategoryConfidencePoint, LearningGrowthPoint, TopCategoryPoint, TopMerchantPoint, TrendPoint } from '../../types';

export function fmtCurrency(n: number) {
  return (n < 0 ? '-₹' : '₹') + Math.abs(n).toLocaleString('en-IN', { maximumFractionDigits: 0 });
}

/** A single labeled bar-list row, sized relative to the largest value in its list -- same visual
 *  primitive reused across every Analytics view below rather than pulling in a charting library
 *  (admin-portal has none today; the removed self-service Analytics.tsx used chart.js in the
 *  User Portal only). Deliberately simple bars, not a line chart -- good enough for a
 *  support-assisted glance at one user's spend, not a general-purpose dashboard. */
export function BarListRow({ label, value, max, formattedValue }: { label: string; value: number; max: number; formattedValue: string }) {
  // Bug fix: Math.max(..., 2) floored every row to a visible 2%-width bar, including a genuine
  // zero -- indistinguishable from a small nonzero value. Only floor when there's an actual
  // nonzero value to make visible at all.
  const pct = max > 0 && value > 0 ? Math.max((value / max) * 100, 2) : 0;
  return (
    <div className="text-xs">
      <div className="flex justify-between mb-0.5">
        <span className="text-ink truncate">{label}</span>
        <span className="text-muted flex-shrink-0 ml-2">{formattedValue}</span>
      </div>
      <div className="h-1.5 rounded-full bg-black/5 overflow-hidden">
        <div className="h-full bg-primary rounded-full" style={{ width: `${pct}%` }} />
      </div>
    </div>
  );
}

/** Per-user analytics -- admin-only now that the self-service Analytics page has been retired.
 *  Reuses AnalyticsService's exact per-user queries via AdminUserAnalyticsController, same read-only
 *  shape as LearningSection above. importStatistics is intentionally not shown here -- it stays on
 *  the signed-in user's own Settings page, see AdminUserAnalyticsController's class comment. */
export function AnalyticsSection({ userId }: { userId: string }) {
  const { data: topMerchants, isLoading: topMerchantsLoading, isError: topMerchantsError } = useQuery<TopMerchantPoint[]>({
    queryKey: ['admin-user-analytics-top-merchants', userId],
    queryFn: () => adminUserAnalyticsApi.topMerchants(userId),
  });
  const { data: topCategories, isLoading: topCategoriesLoading, isError: topCategoriesError } = useQuery<TopCategoryPoint[]>({
    queryKey: ['admin-user-analytics-top-categories', userId],
    queryFn: () => adminUserAnalyticsApi.topCategories(userId),
  });
  const { data: trend, isLoading: trendLoading, isError: trendError } = useQuery<TrendPoint[]>({
    queryKey: ['admin-user-analytics-trend', userId],
    queryFn: () => adminUserAnalyticsApi.trend(userId),
  });
  const { data: categoryConfidence, isLoading: categoryConfidenceLoading, isError: categoryConfidenceError } = useQuery<CategoryConfidencePoint[]>({
    queryKey: ['admin-user-analytics-category-confidence', userId],
    queryFn: () => adminUserAnalyticsApi.categoryConfidence(userId),
  });
  const { data: learningGrowth, isLoading: learningGrowthLoading, isError: learningGrowthError } = useQuery<LearningGrowthPoint[]>({
    queryKey: ['admin-user-analytics-learning-growth', userId],
    queryFn: () => adminUserAnalyticsApi.learningGrowth(userId),
  });

  const isLoading = topMerchantsLoading || topCategoriesLoading || trendLoading || categoryConfidenceLoading || learningGrowthLoading;
  // Bug fix: none of these 5 queries' isError was ever checked -- a failed GET (500, 403, timeout)
  // left isLoading false and data undefined, which every empty-state check below then rendered
  // identically to "this user genuinely has no data," misleading an admin investigating a broken
  // account into thinking the account is just empty.
  const isError = topMerchantsError || topCategoriesError || trendError || categoryConfidenceError || learningGrowthError;
  const maxMerchantSpend = Math.max(1, ...(topMerchants ?? []).map((m) => m.totalSpend));
  const maxCategorySpend = Math.max(1, ...(topCategories ?? []).map((c) => c.totalSpend));
  const maxTrendSpend = Math.max(1, ...(trend ?? []).map((t) => t.totalSpend));

  return (
    <div className="bg-card border border-border rounded-xl2 shadow-card p-6">
      <div className="flex items-center gap-2 mb-3">
        <PieChart size={15} className="text-primary" />
        <h3 className="text-sm font-semibold text-ink">Analytics</h3>
      </div>

      {isLoading && <p className="text-sm text-muted">Loading…</p>}
      {!isLoading && isError && <p className="text-sm text-danger">Couldn't load analytics for this user — please try again later.</p>}

      {!isLoading && !isError && (
        <div className="grid md:grid-cols-2 gap-x-8 gap-y-5">
          <div>
            <p className="text-[11px] uppercase tracking-wide text-muted mb-2">Top merchants</p>
            {(topMerchants ?? []).length === 0 ? (
              <p className="text-xs text-muted">No merchant-attributed spend yet.</p>
            ) : (
              <div className="space-y-2">
                {topMerchants!.map((m) => (
                  <BarListRow key={m.merchantId} label={m.merchantName} value={m.totalSpend} max={maxMerchantSpend}
                    formattedValue={`${fmtCurrency(m.totalSpend)} (${m.transactionCount})`} />
                ))}
              </div>
            )}
          </div>

          <div>
            <p className="text-[11px] uppercase tracking-wide text-muted mb-2">Top categories</p>
            {(topCategories ?? []).length === 0 ? (
              <p className="text-xs text-muted">No categorized spend yet.</p>
            ) : (
              <div className="space-y-2">
                {topCategories!.map((c) => (
                  <BarListRow key={c.categoryId} label={c.categoryName} value={c.totalSpend} max={maxCategorySpend}
                    formattedValue={`${fmtCurrency(c.totalSpend)} (${c.transactionCount})`} />
                ))}
              </div>
            )}
          </div>

          <div>
            <p className="text-[11px] uppercase tracking-wide text-muted mb-2">Spend trend</p>
            {(trend ?? []).every((t) => t.totalSpend === 0) ? (
              <p className="text-xs text-muted">No spend recorded yet.</p>
            ) : (
              <div className="space-y-2">
                {trend!.map((t) => (
                  <BarListRow key={t.month} label={t.month} value={t.totalSpend} max={maxTrendSpend}
                    formattedValue={fmtCurrency(t.totalSpend)} />
                ))}
              </div>
            )}
          </div>

          <div>
            <p className="text-[11px] uppercase tracking-wide text-muted mb-2">Category confidence</p>
            {(categoryConfidence ?? []).length === 0 ? (
              <p className="text-xs text-muted">No learned categories yet.</p>
            ) : (
              <div className="space-y-2">
                {categoryConfidence!.map((c) => (
                  <BarListRow key={c.category} label={c.category} value={c.avgConfidence} max={100}
                    formattedValue={`${c.avgConfidence}% (${c.merchantCount} merchants)`} />
                ))}
              </div>
            )}
          </div>

          <div className="md:col-span-2">
            <p className="text-[11px] uppercase tracking-wide text-muted mb-2">Learning growth</p>
            {(learningGrowth ?? []).every((g) => g.learnedCount === 0 && g.correctedCount === 0) ? (
              <p className="text-xs text-muted">No learning activity yet.</p>
            ) : (
              <dl className="grid grid-cols-3 sm:grid-cols-6 gap-2 text-xs">
                {learningGrowth!.map((g) => (
                  <div key={g.month} className="bg-bg border border-border rounded-lg p-2">
                    <dt className="text-muted mb-0.5">{g.month}</dt>
                    <dd className="text-ink font-medium">{g.learnedCount} learned</dd>
                    <dd className="text-warning">{g.correctedCount} corrected</dd>
                  </div>
                ))}
              </dl>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
