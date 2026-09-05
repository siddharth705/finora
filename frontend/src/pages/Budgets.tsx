import { useEffect, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Doughnut } from 'react-chartjs-2';
import { Chart as ChartJS, ArcElement, Tooltip, Legend } from 'chart.js';
import { PiggyBank, Wallet, CheckCircle2, CalendarClock, PieChart } from 'lucide-react';
import { budgetsApi, categoriesApi, type CategoryOption } from '../api/endpoints';
import type { Budget } from '../types';
import { FinoraCard, EmptyState, Button, Skeleton, MetricCard, Badge, SectionHeader, ChartContainer, baseChartOptions } from '../design-system';
import { useDelayedLoading } from '../hooks/useDelayedLoading';
import { ICON_COMPONENTS, COLOR_HEX } from '../lib/categoryIcons';

ChartJS.register(ArcElement, Tooltip, Legend);

function fmt(n: number) {
  // Negative amounts (e.g. a month where spend exceeded income) must render as "-₹500",
  // not "₹-500" -- string concatenation put the currency symbol before the sign.
  return (n < 0 ? '-₹' : '₹') + Math.round(Math.abs(n)).toLocaleString('en-IN');
}

function daysLeftInMonth(): number {
  const now = new Date();
  const lastDayOfMonth = new Date(now.getFullYear(), now.getMonth() + 1, 0).getDate();
  return lastDayOfMonth - now.getDate();
}

function budgetStatus(pct: number): { label: string; tone: 'success' | 'warning' | 'danger' } {
  if (pct >= 100) return { label: 'Over budget', tone: 'danger' };
  if (pct >= 90) return { label: 'Almost there', tone: 'warning' };
  return { label: 'On track', tone: 'success' };
}

export default function Budgets() {
  const [budgets, setBudgets] = useState<Budget[]>([]);
  const [newCategory, setNewCategory] = useState('');
  const [newLimit, setNewLimit] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const [saving, setSaving] = useState(false);
  // Bug fix: this page had no loading flag at all -- `budgets` started `[]`, which is
  // indistinguishable from "genuinely no budgets set," so the EmptyState rendered immediately on
  // every mount and then popped to real content once the fetch resolved. `loading` now gates the
  // EmptyState branch explicitly; `showSkeleton` (useDelayedLoading) only controls whether a
  // skeleton appears during that gate, not whether the wrong content shows.
  const [loading, setLoading] = useState(true);
  const [categoriesById, setCategoriesById] = useState<Map<string, CategoryOption>>(new Map());
  const queryClient = useQueryClient();

  function load() {
    setLoading(true);
    Promise.all([budgetsApi.list(), categoriesApi.list()])
      .then(([budgetList, categoryList]) => {
        setBudgets(budgetList);
        setCategoriesById(new Map(categoryList.map((c) => [c.id, c])));
      })
      .catch(() => setError('Could not load budgets.'))
      .finally(() => setLoading(false));
  }
  useEffect(load, []);
  const showSkeleton = useDelayedLoading(loading);

  async function addOrUpdate() {
    if (!newCategory || !newLimit) return;
    const limit = parseFloat(newLimit);
    if (!(limit > 0)) {
      setError('Monthly limit must be greater than zero.');
      return;
    }
    setError(null);
    setSaving(true);
    try {
      await budgetsApi.upsert(newCategory, limit);
      setNewCategory('');
      setNewLimit('');
      setSaved(true);
      setTimeout(() => setSaved(false), 2000);
      load();
      // This page keeps its own local `budgets` list (loaded via load() above), but Dashboard
      // reads the same data through TanStack Query under the 'budgets' key (30s staleTime) --
      // without invalidating that cache too, a limit set/changed here wouldn't show up on the
      // Dashboard's Budget Progress widget until the cache aged out on its own. 'dashboard-summary'
      // also gets invalidated since budget overspend feeds its notifications/health score.
      void queryClient.invalidateQueries({ queryKey: ['budgets'] });
      void queryClient.invalidateQueries({ queryKey: ['dashboard-summary'] });
    } catch (e: any) {
      setError(e.response?.data?.message ?? 'Could not save this budget. Try again.');
    } finally {
      setSaving(false);
    }
  }

  const totalSpend = budgets.reduce((sum, b) => sum + b.spentThisMonth, 0);
  const totalLimit = budgets.reduce((sum, b) => sum + b.monthlyLimit, 0);
  const onTrackCount = budgets.filter((b) => b.monthlyLimit > 0 && (b.spentThisMonth / b.monthlyLimit) * 100 < 90).length;

  return (
    <div className="space-y-4">
      {loading ? (
        showSkeleton && (
          <Skeleton.Region label="Loading budget summary" className="grid grid-cols-2 md:grid-cols-4 gap-4">
            {[0, 1, 2, 3].map((i) => <Skeleton.Card key={i} />)}
          </Skeleton.Region>
        )
      ) : (
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          <MetricCard
            label="Total Spend"
            value={fmt(totalSpend)}
            icon={Wallet}
            iconBg="bg-primary-light"
            iconColor="text-primary"
          />
          <MetricCard
            label="Total Budget"
            value={`${fmt(totalSpend)} / ${fmt(totalLimit)}`}
            icon={PiggyBank}
            iconBg="bg-primary-light"
            iconColor="text-primary"
          />
          <MetricCard
            label="Budgets on Track"
            value={`${onTrackCount} of ${budgets.length}`}
            icon={CheckCircle2}
            iconBg="bg-success-bg"
            iconColor="text-success"
          />
          <MetricCard
            label="Days Left"
            value={String(daysLeftInMonth())}
            icon={CalendarClock}
            iconBg="bg-warning-bg"
            iconColor="text-warning"
          />
        </div>
      )}

      <FinoraCard>
        <SectionHeader title="Set a Budget" size="sm" />
        <div className="flex gap-2 items-end">
          <div>
            <label htmlFor="budget-category" className="block text-xs uppercase text-gray-500 mb-1">Category</label>
            <input id="budget-category" value={newCategory} onChange={(e) => setNewCategory(e.target.value)} className="bg-card text-ink border rounded px-2 py-1.5 text-sm" />
          </div>
          <div>
            <label htmlFor="budget-monthly-limit" className="block text-xs uppercase text-gray-500 mb-1">Monthly limit</label>
            <input id="budget-monthly-limit" type="number" value={newLimit} onChange={(e) => setNewLimit(e.target.value)} className="bg-card text-ink border rounded px-2 py-1.5 text-sm" />
          </div>
          <Button onClick={addOrUpdate} loading={saving} size="md" className="uppercase">
            Set Budget
          </Button>
          {saved && <span className="text-success text-xs">Saved.</span>}
        </div>
      </FinoraCard>
      {error && <p className="text-danger text-sm">{error}</p>}

      <div className="grid lg:grid-cols-3 gap-4">
      <FinoraCard padding="sm" className="lg:col-span-2 space-y-3">
        {loading ? (
          showSkeleton && (
            <Skeleton.Region label="Loading budgets">
              <div className="space-y-3">
                {[0, 1, 2].map((i) => (
                  <div key={i} className="flex items-center gap-3">
                    <Skeleton.Circle size={32} />
                    <Skeleton.Text width="w-32" />
                    <Skeleton.Block className="h-2 flex-1" />
                    <Skeleton.Text width="w-36" />
                    <Skeleton.Block className="h-5 w-20" />
                  </div>
                ))}
              </div>
            </Skeleton.Region>
          )
        ) : budgets.length === 0 ? (
          <EmptyState
            icon={PiggyBank}
            iconBg="bg-orange-100"
            iconColor="text-orange-600"
            title="No budgets set"
            desc="Create your first budget above to start tracking spending."
          />
        ) : (
          budgets.map((b) => {
            const pct = b.monthlyLimit > 0 ? Math.min(999, (b.spentThisMonth / b.monthlyLimit) * 100) : 0;
            const barPct = Math.min(100, pct);
            const status = budgetStatus(pct);
            const cat = categoriesById.get(b.categoryId);
            const Icon = ICON_COMPONENTS[cat?.icon ?? 'tag'] ?? Wallet;
            const color = COLOR_HEX[cat?.color ?? 'gray'];
            return (
              <div key={b.id} data-testid="budget-row" className="flex items-center gap-3 text-sm">
                <div className="w-8 h-8 rounded-full flex items-center justify-center flex-shrink-0" style={{ backgroundColor: `${color}26` }}>
                  <Icon size={16} style={{ color }} />
                </div>
                <span className="w-32 flex-shrink-0 truncate">{b.categoryName}</span>
                <div className="flex-1 h-2 bg-black/10 rounded overflow-hidden">
                  <div
                    className={`h-full ${status.tone === 'danger' ? 'bg-danger' : status.tone === 'warning' ? 'bg-warning' : 'bg-success'}`}
                    style={{ width: `${barPct}%` }}
                  />
                </div>
                <span className="w-36 flex-shrink-0 text-right">{fmt(b.spentThisMonth)} / {fmt(b.monthlyLimit)}</span>
                <Badge tone={status.tone} label={status.label} className="flex-shrink-0" />
              </div>
            );
          })
        )}
      </FinoraCard>

        <FinoraCard>
          <SectionHeader title="Spending Breakdown" size="sm" />
          <ChartContainer
            height={220}
            loading={loading}
            loadingLabel="Loading spending breakdown"
            isEmpty={budgets.length === 0}
            emptyState={
              <EmptyState
                icon={PieChart}
                iconBg="bg-primary-light"
                iconColor="text-primary"
                title="No spending to break down yet"
                desc="Set a budget above to see how your spending splits by category."
              />
            }
          >
            <Doughnut
              data={{
                labels: budgets.map((b) => b.categoryName),
                datasets: [{
                  data: budgets.map((b) => b.spentThisMonth),
                  backgroundColor: budgets.map((b) => COLOR_HEX[categoriesById.get(b.categoryId)?.color ?? 'gray']),
                  borderWidth: 0,
                }],
              }}
              options={{ ...baseChartOptions }}
            />
          </ChartContainer>
        </FinoraCard>
      </div>
    </div>
  );
}
