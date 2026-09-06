import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Bar, Line } from 'react-chartjs-2';
import {
  Chart as ChartJS, BarElement, LineElement, PointElement, LinearScale, CategoryScale, Tooltip, Legend, Filler,
} from 'chart.js';
import { Crown, Lock, Store, Tags, Brain, TrendingUp as TrendingUpIcon } from 'lucide-react';
import { analyticsApi, reportsApi } from '../api/endpoints';
import { FinoraCard, EmptyState, SectionHeader, ChartContainer, baseChartOptions, Skeleton } from '../design-system';
import { PremiumFeatureGate } from '../components/PremiumFeatureGate';

ChartJS.register(BarElement, LineElement, PointElement, LinearScale, CategoryScale, Tooltip, Legend, Filler);

function fmt(n: number) {
  // Negative amounts render as "-₹500", not "₹-500" -- same convention as every other page's fmt.
  return (n < 0 ? '-₹' : '₹') + Math.round(Math.abs(n)).toLocaleString('en-IN');
}

function monthLabel(monthStr: string) {
  const [y, m] = monthStr.split('-').map(Number);
  return new Date(y, m - 1, 1).toLocaleDateString('en-US', { month: 'short', year: '2-digit' });
}

function monthLabelLong(monthStr: string) {
  const [y, m] = monthStr.split('-').map(Number);
  return new Date(y, m - 1, 1).toLocaleDateString('en-US', { month: 'long', year: 'numeric' });
}

/** The upgrade prompt shown in place of the whole page for a Free user -- more context than
 *  PremiumFeatureGate's own generic default, since this gates an entire page rather than one
 *  widget. Fails closed exactly like the default (PremiumFeatureGate renders nothing at all while
 *  the entitlements query is loading or erroring; this only ever appears once it has confirmed
 *  the feature is absent). */
function UpgradePrompt() {
  return (
    <FinoraCard padding="lg" className="max-w-lg mx-auto my-12">
      <EmptyState
        icon={Lock}
        iconBg="bg-primary-light"
        iconColor="text-primary"
        title="Advanced Reports is a Plus & Premium feature"
        desc="Top merchants, spend trends, category confidence, and how the categorization engine is learning your habits -- all built from your own transaction history."
        cta={
          <Link
            to="/app/billing"
            className="inline-flex items-center gap-1.5 bg-primary text-on-primary hover:bg-primary-dark rounded-lg px-4 py-2 text-xs font-semibold"
          >
            <Crown size={14} /> View plans
          </Link>
        }
      />
    </FinoraCard>
  );
}

/** A "top N" bar list -- label, a bar sized relative to the largest value in THIS list (not a
 *  running total), and the amount. Same visual language as Reports.tsx's Category Breakdown
 *  rows, adapted for a ranked list rather than a share-of-total. */
function RankedBarList({
  rows, empty,
}: {
  rows: { label: string; sub: string; value: number }[];
  empty: { icon: typeof Store; title: string; desc: string };
}) {
  if (rows.length === 0) {
    return <EmptyState icon={empty.icon} iconBg="bg-primary-light" iconColor="text-primary" title={empty.title} desc={empty.desc} />;
  }
  const max = Math.max(...rows.map((r) => r.value), 1);
  return (
    <div className="space-y-3">
      {rows.map((r) => (
        <div key={r.label} className="grid grid-cols-[1fr_90px] items-center gap-3 text-sm">
          <div className="min-w-0">
            <div className="flex items-baseline justify-between gap-2">
              <span className="text-ink font-medium truncate">{r.label}</span>
              <span className="text-[11px] text-muted flex-shrink-0">{r.sub}</span>
            </div>
            <div className="h-1.5 bg-black/10 rounded overflow-hidden mt-1">
              <div className="h-full bg-primary" style={{ width: `${(r.value / max) * 100}%` }} />
            </div>
          </div>
          <span className="text-right font-medium">{fmt(r.value)}</span>
        </div>
      ))}
    </div>
  );
}

function ListSkeleton() {
  return (
    <div className="space-y-3">
      {Array.from({ length: 5 }, (_, i) => (
        <div key={i} className="grid grid-cols-[1fr_90px] items-center gap-3">
          <Skeleton.Block className="h-6 w-full" />
          <Skeleton.Text width="w-full" className="h-2.5" />
        </div>
      ))}
    </div>
  );
}

/** The gated content -- only ever rendered once PremiumFeatureGate has confirmed ADVANCED_REPORTS
 *  is granted, so none of these queries fire for a Free user. */
function AdvancedReportsContent() {
  const [month, setMonth] = useState<string>(''); // '' = all-time

  const monthsQ = useQuery({ queryKey: ['report-months'], queryFn: () => reportsApi.availableMonths() });
  const topMerchantsQ = useQuery({
    queryKey: ['advanced-reports-top-merchants', month],
    queryFn: () => analyticsApi.topMerchants(month || undefined),
  });
  const topCategoriesQ = useQuery({
    queryKey: ['advanced-reports-top-categories', month],
    queryFn: () => analyticsApi.topCategories(month || undefined),
  });
  const trendQ = useQuery({ queryKey: ['advanced-reports-trend'], queryFn: () => analyticsApi.trend() });
  const confidenceQ = useQuery({ queryKey: ['advanced-reports-confidence'], queryFn: () => analyticsApi.categoryConfidence() });
  const learningQ = useQuery({ queryKey: ['advanced-reports-learning-growth'], queryFn: () => analyticsApi.learningGrowth() });

  const months = monthsQ.data ?? [];

  return (
    <div className="space-y-6">
      <FinoraCard padding="sm" className="flex flex-wrap items-end gap-3 justify-between">
        <div>
          <label htmlFor="advanced-reports-month" className="block text-xs uppercase text-gray-500 mb-1">Period</label>
          <select
            id="advanced-reports-month"
            value={month}
            onChange={(e) => setMonth(e.target.value)}
            className="bg-card text-ink border rounded px-2 py-1.5 text-sm"
          >
            <option value="">All time</option>
            {[...months].reverse().map((m) => <option key={m} value={m}>{monthLabelLong(m)}</option>)}
          </select>
        </div>
        <p className="text-[11px] text-muted max-w-xs">Applies to Top Merchants and Top Categories below. Spend Trend, Category Confidence and Learning Growth always cover your full history.</p>
      </FinoraCard>

      <div className="grid lg:grid-cols-2 gap-6">
        <FinoraCard padding="lg">
          <SectionHeader title="Top Merchants" />
          {topMerchantsQ.isLoading ? <ListSkeleton /> : (
            <RankedBarList
              rows={(topMerchantsQ.data ?? []).map((m) => ({ label: m.merchantName, sub: `${m.transactionCount} txns`, value: m.totalSpend }))}
              empty={{ icon: Store, title: 'No merchant spend yet', desc: 'Import a statement or add transactions to see your top merchants.' }}
            />
          )}
        </FinoraCard>

        <FinoraCard padding="lg">
          <SectionHeader title="Top Categories" />
          {topCategoriesQ.isLoading ? <ListSkeleton /> : (
            <RankedBarList
              rows={(topCategoriesQ.data ?? []).map((c) => ({ label: c.categoryName, sub: `${c.transactionCount} txns`, value: c.totalSpend }))}
              empty={{ icon: Tags, title: 'No categorized spend yet', desc: 'Your top spending categories will appear here.' }}
            />
          )}
        </FinoraCard>
      </div>

      <FinoraCard padding="lg">
        <SectionHeader title="Spend Trend" />
        <p className="text-xs text-muted -mt-2 mb-4">Merchant-attributed spend over your trailing 6 months.</p>
        <ChartContainer
          height={260}
          loading={trendQ.isLoading}
          loadingLabel="Loading spend trend"
          isEmpty={(trendQ.data ?? []).every((p) => p.totalSpend === 0)}
          emptyState={
            <EmptyState icon={TrendingUpIcon} iconBg="bg-primary-light" iconColor="text-primary" title="No trend yet" desc="Once you have a few months of spend, the trend appears here." />
          }
        >
          <Line
            data={{
              labels: (trendQ.data ?? []).map((p) => monthLabel(p.month)),
              datasets: [{
                label: 'Spend', data: (trendQ.data ?? []).map((p) => p.totalSpend),
                borderColor: '#3b82f6', backgroundColor: 'rgba(59,130,246,0.08)', fill: true, tension: 0.3,
              }],
            }}
            options={{ ...baseChartOptions, scales: { y: { ticks: { callback: (v) => fmt(Number(v)) } } } }}
          />
        </ChartContainer>
      </FinoraCard>

      <div className="grid lg:grid-cols-2 gap-6">
        <FinoraCard padding="lg">
          <SectionHeader title="Category Confidence" />
          <p className="text-xs text-muted -mt-2 mb-4">How sure the categorization engine is about each category, on average, across your merchants.</p>
          <ChartContainer
            height={260}
            loading={confidenceQ.isLoading}
            loadingLabel="Loading category confidence"
            isEmpty={(confidenceQ.data ?? []).length === 0}
            emptyState={
              <EmptyState icon={Brain} iconBg="bg-primary-light" iconColor="text-primary" title="Nothing learned yet" desc="Confirm a few categorizations and this fills in." />
            }
          >
            <Bar
              data={{
                labels: (confidenceQ.data ?? []).map((c) => c.category),
                datasets: [{ label: 'Avg. confidence', data: (confidenceQ.data ?? []).map((c) => c.avgConfidence), backgroundColor: '#3b82f6' }],
              }}
              options={{ ...baseChartOptions, scales: { y: { min: 0, max: 100, ticks: { callback: (v) => `${v}%` } } } }}
            />
          </ChartContainer>
        </FinoraCard>

        <FinoraCard padding="lg">
          <SectionHeader title="Learning Growth" />
          <p className="text-xs text-muted -mt-2 mb-4">Categorizations the engine learned on its own vs. ones you corrected, per month.</p>
          <ChartContainer
            height={260}
            loading={learningQ.isLoading}
            loadingLabel="Loading learning growth"
            isEmpty={(learningQ.data ?? []).length === 0}
            emptyState={
              <EmptyState icon={Brain} iconBg="bg-primary-light" iconColor="text-primary" title="No learning history yet" desc="This fills in as you confirm categorizations over time." />
            }
          >
            <Bar
              data={{
                labels: (learningQ.data ?? []).map((p) => monthLabel(p.month)),
                datasets: [
                  { label: 'Learned', data: (learningQ.data ?? []).map((p) => p.learnedCount), backgroundColor: '#16a34a' },
                  { label: 'Corrected', data: (learningQ.data ?? []).map((p) => p.correctedCount), backgroundColor: '#f59e0b' },
                ],
              }}
              options={baseChartOptions}
            />
          </ChartContainer>
        </FinoraCard>
      </div>
    </div>
  );
}

export default function AdvancedReports() {
  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-bold text-ink flex items-center gap-2">
          <Crown size={18} className="text-primary" /> Advanced Reports
        </h1>
        <p className="text-sm text-muted mt-0.5">Deeper analysis of your spending, built from the same engine behind your Dashboard.</p>
      </div>
      <PremiumFeatureGate featureKey="ADVANCED_REPORTS" fallback={<UpgradePrompt />}>
        <AdvancedReportsContent />
      </PremiumFeatureGate>
    </div>
  );
}
