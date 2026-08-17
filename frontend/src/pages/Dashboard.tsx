import { useMemo, useState } from 'react';
import { useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { Line, Doughnut } from 'react-chartjs-2';
import {
  Chart as ChartJS, ArcElement, LineElement, PointElement, LinearScale, CategoryScale, Tooltip, Legend, Filler,
} from 'chart.js';
import {
  Wallet, ArrowDownCircle, ArrowUpCircle, PieChart,
  ShoppingBag, Utensils, Car, Sparkles, Plus, PiggyBank, TrendingUp, TrendingDown, Target, ShieldCheck, Repeat,
  UploadCloud, Receipt, LineChart as LineChartIcon, Mail,
} from 'lucide-react';
import { useAuth } from '../context/AuthContext';
import { BankLogo } from '../components/BankLogo';
import { AddTransactionModal } from '../components/AddTransactionModal';
import { FinoraCard, MetricCard, EmptyState, SectionHeader, QuickActionCard, ChartContainer, Badge, baseChartOptions } from '../design-system';
import {
  dashboardApi, accountsApi, transactionsApi, goalsApi, insightsApi, userApi, budgetsApi, reportsApi, recurringApi,
} from '../api/endpoints';

ChartJS.register(ArcElement, LineElement, PointElement, LinearScale, CategoryScale, Tooltip, Legend, Filler);

function fmt(n: number) {
  // Negative amounts (e.g. a month where spend exceeded income) must render as "-₹500",
  // not "₹-500" -- string concatenation put the currency symbol before the sign.
  return (n < 0 ? '-₹' : '₹') + Math.round(Math.abs(n)).toLocaleString('en-IN', { maximumFractionDigits: 2 });
}

// Reads the current hour in the user's chosen timezone (see Settings) rather than the
// browser's local clock — the two only differ when someone's system clock is set to a
// different zone than the one they actually keep finance-app hours in, but when they do
// differ this used to just be wrong (always whatever the OS thought "now" was), including a
// "Good night" band that a plain morning/afternoon/evening split never had at all.
function greeting(timezone: string | undefined) {
  let hourStr: string;
  try {
    hourStr = new Intl.DateTimeFormat('en-US', { hour: 'numeric', hour12: false, timeZone: timezone || undefined }).format(new Date());
  } catch {
    hourStr = String(new Date().getHours());
  }
  const h = parseInt(hourStr, 10) % 24;
  if (h < 5) return 'Good night';
  if (h < 12) return 'Good morning';
  if (h < 17) return 'Good afternoon';
  if (h < 21) return 'Good evening';
  return 'Good night';
}

// Same three-tier thresholds DashboardService.computeHealthScore already labels server-side
// (Excellent/Good/Fair/Needs Attention at 80/60/40) -- this just maps the label to a color rather
// than re-deriving the cutoffs from the raw score, so the two can't drift apart.
function healthColor(label: string): string {
  switch (label) {
    case 'Excellent': return 'text-success';
    case 'Good': return 'text-primary';
    case 'Fair': return 'text-warning';
    default: return 'text-danger';
  }
}
function healthBarColor(label: string): string {
  switch (label) {
    case 'Excellent': return 'bg-success';
    case 'Good': return 'bg-primary';
    case 'Fair': return 'bg-warning';
    default: return 'bg-danger';
  }
}

const CATEGORY_ICON: Record<string, any> = {
  Dining: Utensils, Shopping: ShoppingBag, Transport: Car, Salary: ArrowDownCircle,
};
const CATEGORY_COLOR: Record<string, string> = {
  Dining: '#ef4444', Shopping: '#f59e0b', Transport: '#111827', Salary: '#16a34a',
};


type CashFlowRange = '3M' | '6M' | '12M';
const RANGE_MONTHS: Record<CashFlowRange, number> = { '3M': 3, '6M': 6, '12M': 12 };

function monthLabel(monthStr: string) {
  const [y, m] = monthStr.split('-').map(Number);
  return new Date(y, m - 1, 1).toLocaleDateString('en-US', { month: 'short', year: '2-digit' });
}

// RecurringDto.nextEstimate is a projection from the merchant's own historical gap
// (lastDate + averageGap), never a confirmed bill date -- "expected", not "due", stays honest
// about that. A past-due estimate (the pattern predicted a charge that hasn't shown up yet, e.g.
// a cancelled subscription with no new import since) reads as "expected around <date>" rather
// than a nonsensical negative day count.
function expectedLabel(dateStr: string): string {
  const days = Math.round((new Date(dateStr + 'T00:00:00').getTime() - new Date().setHours(0, 0, 0, 0)) / 86_400_000);
  const date = new Date(dateStr + 'T00:00:00').toLocaleDateString('en-IN', { day: 'numeric', month: 'short' });
  if (days < 0) return `expected around ${date}`;
  if (days === 0) return 'expected today';
  if (days === 1) return 'expected tomorrow';
  return `expected in ${days} days (${date})`;
}

export default function Dashboard() {
  const { fullName } = useAuth();
  const queryClient = useQueryClient();
  const [cashFlowRange, setCashFlowRange] = useState<CashFlowRange>('6M');
  const [showAddModal, setShowAddModal] = useState(false);

  function onTransactionAdded() {
    setShowAddModal(false);
    void queryClient.invalidateQueries({ queryKey: ['recent-transactions'] });
    void queryClient.invalidateQueries({ queryKey: ['dashboard-summary'] });
    void queryClient.invalidateQueries({ queryKey: ['accounts'] });
    void queryClient.invalidateQueries({ queryKey: ['transactions'] });
  }

  // useQueries runs all these independently (each gets its own cache entry, own retry/error
  // handling, own loading state) rather than one big Promise.all where a single failure
  // blanks the whole dashboard — the insights query already tolerated failure via .catch(),
  // this generalizes that to every query on the page.
  const [summaryQ, accountsQ, recentTxnsQ, goalsQ, insightsQ, settingsQ, budgetsQ, recurringQ] = useQueries({
    queries: [
      { queryKey: ['dashboard-summary'], queryFn: () => dashboardApi.summary() },
      { queryKey: ['accounts'], queryFn: () => accountsApi.list() },
      { queryKey: ['recent-transactions'], queryFn: () => transactionsApi.search({ page: 0, size: 4, sortField: 'date', sortDir: 'desc' }) },
      { queryKey: ['goals'], queryFn: () => goalsApi.list() },
      { queryKey: ['insights'], queryFn: () => insightsApi.get(), retry: false },
      { queryKey: ['user-settings'], queryFn: () => userApi.get() },
      { queryKey: ['budgets'], queryFn: () => budgetsApi.list() },
      { queryKey: ['recurring'], queryFn: () => recurringApi.list(), retry: false },
    ],
  });

  // Cash Flow Overview's time-range selector — backed by real per-month totals (Reports'
  // /reports?month= endpoint), not the flat single-month line this used to render. Available
  // months come from the server already sorted ascending; we take however many the selected
  // range asks for from the tail (most recent), then fetch each month's totals in parallel.
  const { data: availableMonths = [] } = useQuery({
    queryKey: ['report-months'],
    queryFn: () => reportsApi.availableMonths(),
  });
  const monthsInRange = useMemo(
    () => availableMonths.slice(-RANGE_MONTHS[cashFlowRange]),
    [availableMonths, cashFlowRange],
  );
  const monthlyReportsQ = useQueries({
    queries: monthsInRange.map((month) => ({
      queryKey: ['report', month],
      queryFn: () => reportsApi.forMonth(month),
      staleTime: 5 * 60_000, // past months' totals don't change once the month is over
    })),
  });
  const cashFlowLoading = monthlyReportsQ.some((q) => q.isLoading);
  const cashFlowSeries = monthlyReportsQ
    .map((q) => q.data)
    .filter((d): d is NonNullable<typeof d> => !!d);

  const loading = summaryQ.isLoading || accountsQ.isLoading || recentTxnsQ.isLoading || goalsQ.isLoading;
  // Bug fix: TanStack Query's isLoading flips to false once a query SETTLES, including settling
  // with an error -- so a failed summary/accounts/transactions/goals fetch used to fall straight
  // through to `if (!summary) return null`, rendering a blank page on the app's own landing route
  // with zero indication anything went wrong. isError only ever reflects the queries `loading`
  // itself is already built from, so this can't introduce a new spinner-that-never-resolves case.
  const hasError = summaryQ.isError || accountsQ.isError || recentTxnsQ.isError || goalsQ.isError;
  const summary = summaryQ.data;
  const accounts = (accountsQ.data ?? []).filter((acc) => acc.accountType !== 'INVESTMENT').slice(0, 4);
  const recentTxns = recentTxnsQ.data?.content ?? [];
  const goals = (goalsQ.data ?? []).slice(0, 2);
  const budgets = (budgetsQ.data ?? []).slice(0, 3);
  const sentences = insightsQ.data?.sentences ?? [];
  const movers = (insightsQ.data?.movers ?? []).filter((m) => m.pctChange !== null).slice(0, 2);
  // RecurringDto already arrives sorted by nextEstimate (RecurringService's own doc comment) --
  // taking the first few is "soonest due", not an arbitrary truncation.
  const upcomingRecurring = (recurringQ.data ?? []).slice(0, 5);

  if (loading) return <p className="text-muted">Loading…</p>;
  if (hasError || !summary) return <p className="text-muted">Couldn't load your dashboard — please try again later.</p>;

  const firstName = fullName?.split(' ')[0] ?? 'there';

  // D-21: totalElements is the real total this account has, not recentTxnsQ's own 4-row page
  // size -- a brand-new account (or one that connected Gmail/created an account but never
  // actually got any transactions in) needs the same "here's what to do next" treatment a
  // completely fresh signup does. Redesigned from D-21 Step 1's original single-gate welcome
  // screen (which replaced the whole page) to keeping the full dashboard shell visible with a
  // per-section empty state instead -- the shell itself is what shows a new user the shape of the
  // product, not a page that hides it behind one more screen before they've seen anything.
  const isEmpty = (recentTxnsQ.data?.totalElements ?? 0) === 0;

  // Bug 05: these KPIs are the newest month the account has DATA for, which for a product built
  // around importing statements in arrears is routinely not the current calendar month. This page
  // used to assert "this month" and "vs last month" over whichever month that happened to be, so a
  // user who hadn't yet imported August read July's figures as August's. The backend now says which
  // month it is reporting on; this stops guessing and renders it.
  const periodLabel = summary.reportingMonthIsCurrent || !summary.reportingMonth
    ? 'this month'
    : monthLabel(summary.reportingMonth);
  const deltaLabel = summary.reportingMonthIsCurrent || !summary.reportingMonth
    ? 'vs last month'
    : `vs the month before ${monthLabel(summary.reportingMonth)}`;
  const categoryEntries = Object.entries(summary.spendByCategory).sort((a, b) => b[1] - a[1]);
  const totalSpend = categoryEntries.reduce((s, [, v]) => s + v, 0);
  const donutColors = ['#3b82f6', '#16a34a', '#f59e0b', '#8b5cf6', '#ef4444', '#94a3b8'];

  const kpis = [
    { label: 'Total Balance', value: fmt(summary.currentBalance), delta: null as number | null, icon: Wallet, iconBg: 'bg-blue-100', iconColor: 'text-blue-600' },
    { label: 'Total Income', value: fmt(summary.monthlyIncome), delta: summary.incomeDeltaPct, icon: ArrowDownCircle, iconBg: 'bg-green-100', iconColor: 'text-green-600' },
    { label: 'Total Expenses', value: fmt(summary.monthlyExpense), delta: summary.expenseDeltaPct, icon: ArrowUpCircle, iconBg: 'bg-red-100', iconColor: 'text-red-600', invertDelta: true },
    { label: 'Net Savings', value: fmt(summary.netCashFlow), delta: summary.netDeltaPct, icon: PiggyBank, iconBg: 'bg-primary-light', iconColor: 'text-primary' },
    { label: 'Savings Rate', value: summary.savingsRatePct.toFixed(0) + '%', delta: null as number | null, icon: PieChart, iconBg: 'bg-purple-100', iconColor: 'text-purple-600' },
  ];

  return (
    <div>
      <div className="mb-8">
        <h1 className="text-[26px] font-bold text-ink mb-1">{greeting(settingsQ.data?.timezone)}, {firstName}! 👋</h1>
        <p className="text-muted text-sm">
          Here's what's happening with your finances today.
          {!summary.reportingMonthIsCurrent && summary.reportingMonth && (
            // Not a warning -- reporting on the newest month with data is the intended behaviour.
            // What was missing is that nothing said which month, so the figures read as current.
            <> Your latest figures are from <span className="font-medium text-ink">{periodLabel}</span>.</>
          )}
        </p>
      </div>

      {/* KPI cards. Every card gets a deltaLabel (even Balance/Savings Rate, which never carry a
          real delta) so MetricCard renders a muted "— vs last month" instead of a silent gap
          where the line would otherwise just vanish. */}
      <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-5 gap-4 mb-6">
        {kpis.map((k) => (
          <MetricCard
            key={k.label}
            label={k.label}
            value={k.value}
            icon={k.icon}
            iconBg={k.iconBg}
            iconColor={k.iconColor}
            delta={k.delta}
            deltaLabel={deltaLabel}
            invertDelta={k.invertDelta}
          />
        ))}
      </div>

      {/* Financial Health Score — DashboardService.computeHealthScore has always returned this
          (score, label, a 5-component breakdown), sent to the frontend on every load; nothing
          rendered it until now. D-19 Step 1. Hidden entirely while isEmpty -- a score computed
          from zero transactions has nothing real behind it, same reasoning Subscriptions &
          Recurring below already applies to itself. */}
      {!isEmpty && (
      <FinoraCard padding="lg" className="mb-6">
        <div className="flex items-center gap-2 mb-4">
          <div className="w-8 h-8 rounded-full bg-primary-light flex items-center justify-center">
            <ShieldCheck size={15} className="text-primary" />
          </div>
          <h2 className="font-semibold text-ink">Financial Health Score</h2>
        </div>
        <div className="grid md:grid-cols-[auto_1fr] gap-6 items-center">
          <div className="text-center md:text-left">
            <p className={`text-4xl font-bold ${healthColor(summary.healthLabel)}`}>{summary.healthScore}</p>
            <p className="text-xs text-muted">out of 100</p>
            <p className={`text-sm font-medium mt-1 ${healthColor(summary.healthLabel)}`}>{summary.healthLabel}</p>
          </div>
          <div className="space-y-2.5">
            {Object.entries(summary.healthBreakdown).map(([name, score]) => (
              <div key={name}>
                <div className="flex justify-between items-baseline mb-1">
                  <span className="text-xs text-ink">{name}</span>
                  <span className="text-xs text-muted">{Math.round(score)}%</span>
                </div>
                <div className="h-1.5 bg-bg rounded-full overflow-hidden">
                  <div
                    className={`h-full rounded-full ${healthBarColor(summary.healthLabel)}`}
                    style={{ width: `${Math.max(0, Math.min(100, score))}%` }}
                  />
                </div>
              </div>
            ))}
          </div>
        </div>
      </FinoraCard>
      )}

      {/* Cash flow + Spending breakdown */}
      <div className="grid lg:grid-cols-[1.6fr_1fr] gap-6 mb-6">
        <FinoraCard padding="lg">
          {/* Not SectionHeader -- the right side is a range filter, not a "View All" link. */}
          <div className="flex items-center justify-between mb-1">
            <h2 className="font-semibold text-ink">Cash Flow Overview</h2>
            <select
              value={cashFlowRange}
              onChange={(e) => setCashFlowRange(e.target.value as CashFlowRange)}
              className="text-xs border border-border rounded-lg px-2.5 py-1.5 text-muted"
            >
              <option value="3M">Last 3 Months</option>
              <option value="6M">Last 6 Months</option>
              <option value="12M">Last 12 Months</option>
            </select>
          </div>
          <p className="text-sm text-muted mb-4">
            You've earned {fmt(summary.monthlyIncome)} and spent {fmt(summary.monthlyExpense)} {periodLabel}.
          </p>
          <ChartContainer
            height={256}
            loading={cashFlowLoading}
            loadingLabel="Loading trend…"
            isEmpty={cashFlowSeries.length === 0}
            emptyState={
              <EmptyState
                icon={LineChartIcon}
                iconBg="bg-primary-light"
                iconColor="text-primary"
                title="No data yet"
                desc="Import a statement or add transactions to see your cash flow trend."
                cta={
                  <Link
                    to="/app/import"
                    className="inline-flex items-center gap-1.5 bg-primary text-white hover:bg-primary-dark rounded-lg px-4 py-2 text-xs font-semibold"
                  >
                    <UploadCloud size={14} /> Import Statement
                  </Link>
                }
              />
            }
          >
            <CashFlowChart series={cashFlowSeries} />
          </ChartContainer>
        </FinoraCard>

        <FinoraCard padding="lg" className="flex flex-col">
          <SectionHeader title="Spending Breakdown" viewAllTo="/app/reports" />
          {categoryEntries.length === 0 ? (
            <div className="flex-1 flex items-center justify-center">
              <EmptyState
                icon={PieChart}
                iconBg="bg-purple-100"
                iconColor="text-purple-600"
                title="No spending data yet"
                desc="Your top spending categories will appear here."
                cta={
                  <Link to="/app/reports" className="inline-block border border-border text-ink hover:bg-bg rounded-lg px-4 py-2 text-xs font-semibold">
                    View Reports
                  </Link>
                }
              />
            </div>
          ) : (
            <>
              <div className="relative w-40 h-40 mx-auto mb-4">
                <Doughnut
                  data={{
                    labels: categoryEntries.map(([k]) => k),
                    datasets: [{ data: categoryEntries.map(([, v]) => v), backgroundColor: categoryEntries.map((_, i) => donutColors[i % donutColors.length]), borderWidth: 0 }],
                  }}
                  options={{ cutout: '72%', plugins: { legend: { display: false } } }}
                />
                <div className="absolute inset-0 flex flex-col items-center justify-center">
                  <span className="text-lg font-bold text-ink">{fmt(totalSpend)}</span>
                  <span className="text-[11px] text-muted">Total</span>
                </div>
              </div>
              <div className="space-y-2 flex-1">
                {categoryEntries.slice(0, 6).map(([name, val], i) => (
                  <div key={name} className="flex items-center justify-between text-xs">
                    <span className="flex items-center gap-2 text-ink">
                      <span className="w-2 h-2 rounded-full" style={{ background: donutColors[i % donutColors.length] }} />
                      {name}
                    </span>
                    <span className="text-muted">{((val / totalSpend) * 100).toFixed(0)}%</span>
                    <span className="font-medium text-ink">{fmt(val)}</span>
                  </div>
                ))}
              </div>
              <Link to="/app/reports" className="mt-4 text-center text-xs font-medium text-primary bg-primary-light rounded-lg py-2.5">
                View Full Report →
              </Link>
            </>
          )}
        </FinoraCard>
      </div>

      {/* Accounts / Recent Transactions / Budget Progress / Goals */}
      <div className="grid md:grid-cols-2 xl:grid-cols-4 gap-6 mb-6">
        <FinoraCard>
          <SectionHeader title="Accounts Overview" viewAllTo="/app/accounts" size="sm" />
          <div className="space-y-3">
            {accounts.length === 0 ? (
              <EmptyState
                icon={Wallet}
                iconBg="bg-blue-100"
                iconColor="text-blue-600"
                title="No accounts yet"
                desc="Add your bank accounts to get a complete view."
                cta={
                  <Link to="/app/setup" className="inline-block bg-primary text-white hover:bg-primary-dark rounded-lg px-4 py-2 text-xs font-semibold">
                    + Add Account
                  </Link>
                }
              />
            ) : accounts.map((a) => (
              <div key={a.id} className="flex items-center gap-3">
                <BankLogo bank={a.bank} size={36} />
                <div className="min-w-0 flex-1">
                  <p className="text-sm font-medium text-ink truncate">{a.name}</p>
                  <p className="text-[11px] text-muted truncate">
                    {a.accountNumberMasked ? a.accountNumberMasked : a.accountType.replace('_', ' ')}
                  </p>
                </div>
                <span className="text-sm font-semibold text-ink flex-shrink-0">{fmt(a.balance)}</span>
              </div>
            ))}
          </div>
        </FinoraCard>

        <FinoraCard>
          <SectionHeader title="Recent Transactions" viewAllTo="/app/transactions" size="sm" />
          <div className="space-y-3">
            {recentTxns.length === 0 ? (
              <EmptyState
                icon={Receipt}
                iconBg="bg-green-100"
                iconColor="text-green-600"
                title="No transactions yet"
                desc="Your recent transactions will appear here."
                cta={
                  <button
                    type="button"
                    onClick={() => setShowAddModal(true)}
                    className="bg-primary text-white hover:bg-primary-dark rounded-lg px-4 py-2 text-xs font-semibold"
                  >
                    + Add Transaction
                  </button>
                }
              />
            ) : recentTxns.map((t) => {
              const Icon = CATEGORY_ICON[t.categoryName] ?? ShoppingBag;
              const color = t.type === 'INCOME' ? '#16a34a' : (CATEGORY_COLOR[t.categoryName] ?? '#2563EB');
              return (
                <div key={t.id} className="flex items-center gap-3">
                  <div className="w-9 h-9 rounded-full flex items-center justify-center flex-shrink-0" style={{ background: color + '20' }}>
                    <Icon size={16} style={{ color }} />
                  </div>
                  <div className="min-w-0 flex-1">
                    <p className="text-sm font-medium text-ink truncate">{t.description || t.merchant}</p>
                    <p className="text-[11px] text-muted">{t.date}</p>
                  </div>
                  <span className={`text-sm font-semibold flex-shrink-0 ${t.type === 'INCOME' ? 'text-success' : 'text-danger'}`}>
                    {t.type === 'INCOME' ? '+' : '-'}{fmt(t.amount)}
                  </span>
                </div>
              );
            })}
          </div>
        </FinoraCard>

        <FinoraCard>
          <SectionHeader title="Budget Progress" viewAllTo="/app/budgets" size="sm" />
          <div className="space-y-4">
            {budgets.length === 0 ? (
              <EmptyState
                icon={PiggyBank}
                iconBg="bg-orange-100"
                iconColor="text-orange-600"
                title="No budgets set"
                desc="Create budgets to track your spending and stay on track."
                cta={
                  <Link to="/app/budgets" className="inline-block bg-primary text-white hover:bg-primary-dark rounded-lg px-4 py-2 text-xs font-semibold">
                    Create Budget
                  </Link>
                }
              />
            ) : (
              <>
                {budgets.map((b) => {
                  const pct = b.monthlyLimit > 0 ? Math.min(100, (b.spentThisMonth / b.monthlyLimit) * 100) : 0;
                  const over = b.spentThisMonth > b.monthlyLimit;
                  return (
                    <div key={b.id}>
                      <div className="flex justify-between items-baseline mb-1.5">
                        <span className="text-sm font-medium text-ink">{b.categoryName}</span>
                        <span className={`text-xs ${over ? 'text-danger font-medium' : 'text-muted'}`}>{pct.toFixed(0)}%</span>
                      </div>
                      <div className="h-1.5 bg-bg rounded-full overflow-hidden mb-1">
                        <div className={`h-full rounded-full ${over ? 'bg-danger' : 'bg-primary'}`} style={{ width: `${pct}%` }} />
                      </div>
                      <p className="text-[11px] text-muted">{fmt(b.spentThisMonth)} of {fmt(b.monthlyLimit)}</p>
                    </div>
                  );
                })}
                <Link to="/app/budgets" className="block text-center text-xs font-medium text-primary bg-primary-light rounded-lg py-2.5">
                  <Target size={12} className="inline mr-1 -mt-0.5" /> Manage Budgets
                </Link>
              </>
            )}
          </div>
        </FinoraCard>

        <FinoraCard>
          <SectionHeader title="Goals" viewAllTo="/app/goals" size="sm" />
          <div className="space-y-4">
            {goals.length === 0 ? (
              <EmptyState
                icon={Target}
                iconBg="bg-red-100"
                iconColor="text-red-600"
                title="No goals yet"
                desc="Set your financial goals and achieve them step by step."
                cta={
                  <Link to="/app/goals" className="inline-block bg-primary text-white hover:bg-primary-dark rounded-lg px-4 py-2 text-xs font-semibold">
                    + Create Goal
                  </Link>
                }
              />
            ) : (
              <>
                {goals.map((g) => {
                  const pct = g.targetAmount > 0 ? Math.min(100, (g.currentAmount / g.targetAmount) * 100) : 0;
                  return (
                    <div key={g.id}>
                      <div className="flex justify-between items-baseline mb-1.5">
                        <span className="text-sm font-medium text-ink">{g.name}</span>
                        <span className="text-xs text-muted">{pct.toFixed(0)}%</span>
                      </div>
                      <div className="h-1.5 bg-bg rounded-full overflow-hidden mb-1">
                        <div className="h-full bg-primary rounded-full" style={{ width: `${pct}%` }} />
                      </div>
                      <p className="text-[11px] text-muted">{fmt(g.currentAmount)} of {fmt(g.targetAmount)}</p>
                    </div>
                  );
                })}
                <Link to="/app/goals" className="block text-center text-xs font-medium text-primary bg-primary-light rounded-lg py-2.5">
                  + Create New Goal
                </Link>
              </>
            )}
          </div>
        </FinoraCard>
      </div>

      {/* AI Insights + Quick Actions. AI Insights used to hide entirely with nothing computed yet
          -- now always visible, generic starter tips in place of real sentences/movers, so a new
          user sees the section exists rather than it silently not being there. Quick Actions is
          new: a shortcut grid to the same destinations scattered across this page's own empty
          states and CTAs, gathered in one place the way the reference design has it. */}
      <div className="grid lg:grid-cols-[1.6fr_1fr] gap-6 mb-6">
        <FinoraCard padding="none" className="overflow-hidden">
          <div className="flex items-center justify-between px-6 pt-5 pb-4">
            <div className="flex items-center gap-2">
              <div className="w-8 h-8 rounded-full bg-primary-light flex items-center justify-center">
                <Sparkles size={15} className="text-primary" />
              </div>
              <h2 className="font-semibold text-ink">AI Insights</h2>
              <Badge label="Beta" />
            </div>
            <Link to="/app/insights" className="bg-primary text-white text-xs font-semibold rounded-lg px-4 py-2">
              View Insights
            </Link>
          </div>
          {sentences.length === 0 && movers.length === 0 ? (
            <div className="px-6 pb-5">
              <p className="text-xs text-muted mb-3">Get personalized insights to improve your financial health.</p>
              <ul className="space-y-2">
                {[
                  'Upload or import more transactions to get AI-powered insights.',
                  'Track your spending to identify patterns and save more.',
                  'Set budgets to stay in control of your finances.',
                ].map((tip) => (
                  <li key={tip} className="text-sm text-ink flex items-start gap-2">
                    <span className="w-1.5 h-1.5 rounded-full bg-primary mt-1.5 flex-shrink-0" />
                    {tip}
                  </li>
                ))}
              </ul>
            </div>
          ) : (
            <div className="px-6 pb-5 grid md:grid-cols-2 gap-x-8 gap-y-3">
              {sentences.length > 0 && (
                <div className="space-y-2">
                  {sentences.slice(0, 3).map((s, i) => (
                    <p key={i} className="text-sm text-ink flex items-start gap-2">
                      <span className="w-1.5 h-1.5 rounded-full bg-primary mt-1.5 flex-shrink-0" />
                      {s}
                    </p>
                  ))}
                </div>
              )}
              {movers.length > 0 && (
                <div className="space-y-2">
                  {/* Deliberately no period in this heading. These movers come from the INSIGHTS
                      query, which resolves its own reporting month over a differently-filtered set
                      (expenses only, where the dashboard also counts income), so the two can pick
                      different months for an account whose newest month holds only income.
                      `periodLabel` describes the dashboard's month and would be asserting a period
                      this list does not necessarily belong to -- the same class of claim as Bug 05.
                      The insight sentences rendered above already carry their own period wording,
                      built server-side by InsightsService. */}
                  <p className="text-[11px] uppercase tracking-wide text-muted mb-1">Biggest movers</p>
                  {movers.map((m) => (
                    <div key={m.category} className="flex items-center justify-between text-sm">
                      <span className="text-ink">{m.category}</span>
                      <span className={`flex items-center gap-1 font-medium ${(m.pctChange ?? 0) >= 0 ? 'text-danger' : 'text-success'}`}>
                        {(m.pctChange ?? 0) >= 0 ? <TrendingUp size={13} /> : <TrendingDown size={13} />}
                        {Math.abs(m.pctChange ?? 0).toFixed(0)}%
                      </span>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}
        </FinoraCard>

        <FinoraCard>
          <h2 className="font-semibold text-ink text-sm mb-4">Quick Actions</h2>
          <div className="grid grid-cols-3 gap-3">
            {[
              { icon: UploadCloud, label: 'Import Statement', to: '/app/import' },
              { icon: Plus, label: 'Add Transaction', onClick: () => setShowAddModal(true) },
              // D-21 originally scoped three setup paths (import, Gmail, manual) -- Gmail has no
              // per-section empty-state card of its own the way Import (Cash Flow) and Add
              // Transaction (Recent Transactions) do, so it lives here instead rather than being
              // dropped from the redesign entirely.
              { icon: Mail, label: 'Connect Gmail', to: '/app/settings' },
              { icon: Target, label: 'Create Budget', to: '/app/budgets' },
              { icon: PieChart, label: 'View Reports', to: '/app/reports' },
              { icon: TrendingUp, label: 'Manage Goals', to: '/app/goals' },
              { icon: LineChartIcon, label: 'Investments', to: '/app/investments' },
            ].map((action) => (
              <QuickActionCard key={action.label} icon={action.icon} label={action.label} to={action.to} onClick={action.onClick} />
            ))}
          </div>
        </FinoraCard>
      </div>

      {/* Subscriptions & Recurring Payments — RecurringService.detectForUser has computed this
          (merchant, cadence, average amount, projected next charge) since before this session,
          consumed by nothing until now: the Ledger/Reports "recurring" badge is the only place
          this data ever reached a screen. Read-only surfacing, same as Financial Health Score and
          AI Insights above -- no new detection logic, just showing what already exists. */}
      {upcomingRecurring.length > 0 && (
        <FinoraCard padding="none" className="mb-6 overflow-hidden">
          <div className="flex items-center gap-2 px-6 pt-5 pb-4">
            <div className="w-8 h-8 rounded-full bg-primary-light flex items-center justify-center">
              <Repeat size={15} className="text-primary" />
            </div>
            <h2 className="font-semibold text-ink">Subscriptions &amp; Recurring Payments</h2>
          </div>
          <ul className="px-6 pb-5 space-y-3">
            {upcomingRecurring.map((r) => (
              <li key={r.merchant} className="flex items-center justify-between text-sm">
                <div>
                  <span className="text-ink font-medium">{r.merchant}</span>
                  <Badge label={r.label} className="ml-2" />
                </div>
                <div className="text-right">
                  <p className="text-ink font-medium">{fmt(r.averageAmount)}</p>
                  <p className="text-xs text-muted">{expectedLabel(r.nextEstimate)}</p>
                </div>
              </li>
            ))}
          </ul>
        </FinoraCard>
      )}

      {/* Floating action button — was purely decorative before (no onClick at all). Statement
          import is the primary way new data is meant to enter Finora, so that's what this
          now opens rather than, say, a generic "add transaction" menu. */}
      <Link
        to="/app/import"
        className="fixed bottom-8 right-8 w-14 h-14 rounded-full bg-primary text-white shadow-soft flex items-center justify-center hover:bg-primary-dark"
        title="Import a bank or credit card statement"
      >
        <Plus size={24} />
      </Link>

      {showAddModal && (
        <AddTransactionModal onClose={() => setShowAddModal(false)} onSaved={onTransactionAdded} />
      )}
    </div>
  );
}

function CashFlowChart({ series }: { series: { month: string; income: number; expense: number }[] }) {
  const labels = series.map((s) => monthLabel(s.month));
  return (
    <Line
      data={{
        labels,
        datasets: [
          { label: 'Income', data: series.map((s) => s.income), borderColor: '#16a34a', backgroundColor: 'rgba(22,163,74,0.08)', fill: true, tension: 0.3 },
          { label: 'Expenses', data: series.map((s) => s.expense), borderColor: '#ef4444', backgroundColor: 'rgba(239,68,68,0.08)', fill: true, tension: 0.3 },
        ],
      }}
      options={{
        ...baseChartOptions,
        // fmt(), not string concatenation: a negative tick must render as "-₹500", not "₹-500".
        // Dormant while this chart plots only income and expense (non-negative by construction in
        // DashboardService) and live the moment the component or this options object is reused
        // for a net series -- which is exactly how the same bug got everywhere else it was fixed.
        scales: { y: { ticks: { callback: (v) => fmt(Number(v)) } } },
      }}
    />
  );
}

