import { useMemo, useState } from 'react';
import { useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { motion, useReducedMotion } from 'framer-motion';
import { Line, Doughnut } from 'react-chartjs-2';
import {
  Chart as ChartJS, ArcElement, LineElement, PointElement, LinearScale, CategoryScale, Tooltip, Legend, Filler,
} from 'chart.js';
import {
  Wallet, ArrowDownCircle, ArrowUpCircle, PieChart,
  ShoppingBag, Sparkles, Plus, PiggyBank, TrendingUp, TrendingDown, Target, ShieldCheck, Repeat,
  UploadCloud, Receipt, LineChart as LineChartIcon, Mail, AlertTriangle, ListChecks, Copy, BadgeCheck,
} from 'lucide-react';
import { useAuth } from '../context/AuthContext';
import { BankLogo } from '../components/BankLogo';
import { MerchantLogo } from '../components/MerchantLogo';
import { AddTransactionModal } from '../components/AddTransactionModal';
import { FinancialJourney } from '../components/FinancialJourney';
import { FinoraCard, MetricCard, EmptyState, SectionHeader, QuickActionCard, ChartContainer, Badge, baseChartOptions, Button, Skeleton } from '../design-system';
import { useDelayedLoading } from '../hooks/useDelayedLoading';
import { ICON_COMPONENTS, COLOR_HEX } from '../lib/categoryIcons';
import {
  dashboardApi, accountsApi, transactionsApi, categoriesApi, goalsApi, insightsApi, userApi, budgetsApi, reportsApi, recurringApi,
  type CategoryOption,
} from '../api/endpoints';

ChartJS.register(ArcElement, LineElement, PointElement, LinearScale, CategoryScale, Tooltip, Legend, Filler);

// react-router's Link wrapped for framer-motion gesture props -- same technique as
// QuickActionCard.tsx, used here for the floating action button (the other named hoverScale
// adopter in the animation-polish roadmap alongside Quick Action tiles).
const MotionLink = motion.create(Link);

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
// Same 80/60/40 cutoffs as healthColor above, applied to ONE breakdown item's own
// score rather than the overall label -- every item used to inherit the overall label's color, so
// a perfect sub-score (e.g. Debt Score 100 for a user with no credit cards) rendered as a
// full-width RED bar whenever the overall health score was "Needs Attention", reading as "maxed
// out" regardless of what that item's own number said.
function healthItemBarColor(score: number): string {
  if (score >= 80) return 'bg-success';
  if (score >= 60) return 'bg-primary';
  if (score >= 40) return 'bg-warning';
  return 'bg-danger';
}

// Same 80/60/40 cutoffs and label vocabulary as the health score above (Excellent/Good/Fair/Needs
// Attention), reused rather than invented fresh -- Categorization Confidence is on the same 0-100
// scale, and a second vocabulary for the same range would just be one more thing to learn.
function scoreLabel(score: number): string {
  if (score >= 80) return 'Excellent';
  if (score >= 60) return 'Good';
  if (score >= 40) return 'Fair';
  return 'Needs Attention';
}

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
  // Recent Transactions' icon/color used to key off categoryName against a 4-entry hardcoded map
  // (predates custom categories, and covered only 4 of the 25 default categories even before user-
  // created ones existed). Looked up by categoryId instead so every category -- default or custom
  // -- renders its own real, backend-assigned icon/color token.
  //
  // On the shared ['categories'] key rather than its own useState+useEffect: this page also
  // renders AskOnceCard and MerchantGroupReviewCard, each of which mounts CategoryComboboxes
  // reading the same key, so one fetch serves all of them. It also picks up react-query's error
  // handling, replacing a bare .then() with no .catch() at all -- a rejected promise there was an
  // unhandled rejection, and the icons simply fell back to the default forever with no signal.
  const categoriesQ = useQuery({ queryKey: ['categories'], queryFn: () => categoriesApi.list(), retry: false });
  const categoriesById: Record<string, CategoryOption> = useMemo(
    () => Object.fromEntries((categoriesQ.data ?? []).map((c) => [c.id, c])),
    [categoriesQ.data],
  );

  const [confirmingDuplicateId, setConfirmingDuplicateId] = useState<string | null>(null);
  const [duplicateConfirmError, setDuplicateConfirmError] = useState<string | null>(null);
  // Which ONE Financial Health Score breakdown row (if any) has its "Why?" detail expanded --
  // same single-open-at-a-time simplicity as the rest of this page's disclosures, just tracked by
  // component name here rather than each row owning its own state, since these five rows are
  // rendered inline rather than as their own component.
  const [expandedHealthDetail, setExpandedHealthDetail] = useState<string | null>(null);

  // BH-027's own service-layer doc comment: "the user asked for this row to count, so it counts
  // now." transactionsApi.confirmNotDuplicate already existed and already worked -- this is the
  // first UI anywhere in the product that calls it. dashboard-summary is invalidated so the card
  // (and every KPI the reinstated transaction now counts toward) reflects the change immediately;
  // recent-transactions/transactions too, since the row itself just changed status.
  async function handleConfirmNotDuplicate(transactionId: string) {
    setConfirmingDuplicateId(transactionId);
    setDuplicateConfirmError(null);
    try {
      await transactionsApi.confirmNotDuplicate(transactionId);
      void queryClient.invalidateQueries({ queryKey: ['dashboard-summary'] });
      void queryClient.invalidateQueries({ queryKey: ['recent-transactions'] });
      void queryClient.invalidateQueries({ queryKey: ['transactions'] });
    } catch {
      setDuplicateConfirmError("Couldn't update this transaction. Please try again.");
    } finally {
      setConfirmingDuplicateId(null);
    }
  }

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

  // Animation-polish roadmap §3 priority 1 / §1's "section-scoped loading" rule: split the old
  // blanket 4-query gate so whichever section's data arrives first renders first, instead of
  // everything waiting on the slowest one. Not a full split, though -- `isEmpty` below reads
  // recentTxnsQ.data directly and gates several OTHER sections (Financial Health Score,
  // Categorization Confidence, Next Actions, the Limited History banner), so recentTxnsQ is
  // structurally load-bearing for the page shell, not just its own card. Decoupling it the way
  // accounts/goals/budgets are decoupled below would let `isEmpty` default to `true` off an
  // unresolved query, hiding those sections for a user who actually has data -- trading the
  // original flash-of-wrong-content bug for a new one in the opposite direction. summaryQ and
  // recentTxnsQ stay a blocking pair; accountsQ/goalsQ/budgetsQ (below, each only used within its
  // own card) become independently-loading sections.
  const blockingLoading = summaryQ.isLoading || recentTxnsQ.isLoading;
  // Bug fix: TanStack Query's isLoading flips to false once a query SETTLES, including settling
  // with an error -- so a failed summary/accounts/transactions/goals fetch used to fall straight
  // through to `if (!summary) return null`, rendering a blank page on the app's own landing route
  // with zero indication anything went wrong. isError only ever reflects the queries `loading`
  // itself is already built from, so this can't introduce a new spinner-that-never-resolves case.
  const hasError = summaryQ.isError || accountsQ.isError || recentTxnsQ.isError || goalsQ.isError;
  const showPageSkeleton = useDelayedLoading(blockingLoading);
  const showAccountsSkeleton = useDelayedLoading(accountsQ.isLoading);
  const showGoalsSkeleton = useDelayedLoading(goalsQ.isLoading);
  const showBudgetsSkeleton = useDelayedLoading(budgetsQ.isLoading);
  const prefersReducedMotion = useReducedMotion();
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

  if (blockingLoading) return showPageSkeleton ? <DashboardSkeleton /> : null;
  if (hasError || !summary) {
    return <p className="text-muted">Couldn’t load your dashboard — please try again later.</p>;
  }

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

  // incomeDeltaPct/expenseDeltaPct/netDeltaPct share one gate on the backend (DashboardService
  // computes a single priorMonthReliable boolean and applies it to all three), so there's one
  // reason to explain, not three -- computed once here and handed to whichever of the three KPI
  // cards below actually has a nulled-out delta to explain. Balance/Savings Rate never carry a
  // gate reason: their "—" is "this KPI has no delta concept at all", not a withheld comparison.
  const comparisonGateReasonText = summary.comparisonGateReason === 'PARTIAL_PRIOR_MONTH'
    ? "Last month's data only covers part of the month, so comparing it wouldn't be a fair like-for-like."
    : summary.comparisonGateReason === 'TOO_FEW_PRIOR_TRANSACTIONS'
      ? `Last month has fewer than ${summary.comparisonGateMinTransactions} transactions, too few to compare reliably.`
      : null;

  // The categories actually behind a real Total Expenses delta -- e.g. "expenses up 12%" alone
  // never says WHY; DashboardService.expenseCategoryMovers already ranked the real contributors,
  // this just renders each into one line. Empty whenever expenseDeltaPct is null (nothing to
  // explain about a hidden number -- comparisonGateReasonText above already covers that case).
  const expenseMoverLines = summary.expenseCategoryMovers.map((m) => {
    const pctText = m.pctChange === null ? `new ${periodLabel}` : `${m.pctChange >= 0 ? '+' : ''}${m.pctChange.toFixed(0)}%`;
    return `${m.category}: ${fmt(m.currentAmount)} vs ${fmt(m.priorAmount)} (${pctText})`;
  });

  const kpis = [
    { label: 'Total Balance', value: fmt(summary.currentBalance), delta: null as number | null, icon: Wallet, iconBg: 'bg-blue-100', iconColor: 'text-blue-600' },
    { label: 'Total Income', value: fmt(summary.monthlyIncome), delta: summary.incomeDeltaPct, icon: ArrowDownCircle, iconBg: 'bg-green-100', iconColor: 'text-green-600', gateReasonText: comparisonGateReasonText },
    { label: 'Total Expenses', value: fmt(summary.monthlyExpense), delta: summary.expenseDeltaPct, icon: ArrowUpCircle, iconBg: 'bg-red-100', iconColor: 'text-red-600', invertDelta: true, gateReasonText: comparisonGateReasonText, moverLines: expenseMoverLines },
    { label: 'Net Savings', value: fmt(summary.netCashFlow), delta: summary.netDeltaPct, icon: PiggyBank, iconBg: 'bg-primary-light', iconColor: 'text-primary', gateReasonText: comparisonGateReasonText },
    { label: 'Savings Rate', value: summary.savingsRatePct.toFixed(0) + '%', delta: null as number | null, icon: PieChart, iconBg: 'bg-purple-100', iconColor: 'text-purple-600' },
  ];

  return (
    <div>
      <div className="relative overflow-hidden bg-card rounded-xl2 border border-border shadow-card mb-8 px-6 py-6 lg:pr-4">
        <div className="relative z-10 lg:max-w-[62%]">
          <h1 className="text-[26px] font-bold text-ink mb-1">{greeting(settingsQ.data?.timezone)}, {firstName}! 👋</h1>
          <p className="text-muted text-sm mb-4">
            Here's what's happening with your finances today.
            {!summary.reportingMonthIsCurrent && summary.reportingMonth && (
              // Not a warning -- reporting on the newest month with data is the intended behaviour.
              // What was missing is that nothing said which month, so the figures read as current.
              <> Your latest figures are from <span className="font-medium text-ink">{periodLabel}</span>.</>
            )}
          </p>
          <div className="flex flex-wrap gap-2">
            {summary.healthScoreAvailable && (
              <span className={`inline-flex items-center gap-1.5 rounded-full border border-border bg-bg px-3 py-1.5 text-xs font-semibold ${healthColor(summary.healthLabel!)}`}>
                <ShieldCheck size={13} /> Financial Health: {summary.healthLabel} · {summary.healthScore}/100
              </span>
            )}
            <span className="inline-flex items-center gap-1.5 rounded-full border border-border bg-bg px-3 py-1.5 text-xs font-semibold text-primary">
              <PiggyBank size={13} /> Savings rate {summary.savingsRatePct.toFixed(0)}%
            </span>
          </div>
        </div>
        {/* Purely decorative -- the illustration carries no information the heading/chips above
            don't already state, so the whole region is hidden from assistive tech rather than
            given (unhelpful, made-up) alt text. Hidden below `lg`: there isn't room for a side
            illustration without shrinking or overlapping the greeting text on a narrow viewport. */}
        <div
          data-testid="dashboard-hero-illustration"
          aria-hidden="true"
          className="hidden lg:block absolute inset-y-0 right-0 w-[42%]"
        >
          <svg viewBox="0 0 380 220" className="absolute inset-0 w-full h-full" preserveAspectRatio="xMaxYMid slice">
            <polygon
              points="0,220 40,150 70,158 110,120 150,138 190,100 230,122 270,86 310,108 340,72 380,92 380,220"
              className="fill-primary/[0.05]"
            />
            <polyline
              points="0,170 40,150 70,158 110,120 150,138 190,100 230,122 270,86 310,108 340,72 380,92"
              className="stroke-primary/[0.35]"
              fill="none" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round"
            />
            <circle cx="340" cy="72" r="4.5" className="fill-primary" />
          </svg>
        </div>
      </div>

      {/* Limited-history banner. The KPI deltas and health score below are real, computed numbers
          -- neither is hidden here -- but both are prone to thin-data artifacts this far below
          limitedHistoryMonthFloor: a trend delta dividing against a near-empty prior month (see
          pct() in DashboardService, still capable of a 900%+ swing off one stray transaction), and
          a health score built from too few comparable months (see the Spend Consistency / Cash
          Flow Stability partial-month fix). Shown once, above everything it explains, rather than
          leaving a user to notice the numbers look strange and wonder why. Hidden once isEmpty --
          the zero-transaction empty state below already covers that case on its own terms. */}
      {!isEmpty && summary.limitedHistory && (
        <div className="bg-warning-bg border border-warning/30 rounded-xl2 px-5 py-3.5 flex items-start gap-2.5 mb-6">
          <AlertTriangle size={16} className="text-warning flex-shrink-0 mt-0.5" />
          <div>
            <p className="text-sm font-medium text-ink">Limited financial history</p>
            <p className="text-xs text-muted mt-0.5">
              Based on {summary.statementCount} statement{summary.statementCount === 1 ? '' : 's'} across{' '}
              {summary.accountCount} account{summary.accountCount === 1 ? '' : 's'} and{' '}
              {summary.historyMonthCount} month{summary.historyMonthCount === 1 ? '' : 's'} of activity.
              Trends and the Financial Health Score below may be unreliable until at least{' '}
              {summary.limitedHistoryMonthFloor} months of history are imported.
            </p>
          </div>
        </div>
      )}

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
            gateReasonText={k.gateReasonText}
            moverLines={k.moverLines}
            variant="elevated"
          />
        ))}
      </div>

      {/* Financial Health Score — DashboardService.computeHealthScore has always returned this
          (score, label, a 5-component breakdown), sent to the frontend on every load; nothing
          rendered it until now. D-19 Step 1. Hidden entirely while isEmpty -- a score computed
          from zero transactions has nothing real behind it, same reasoning Subscriptions &
          Recurring below already applies to itself. D-25 PR3-A: below isEmpty is not the whole
          gap -- a handful of transactions can still score under 40 ("Needs Attention") by
          construction, a harsh first impression over incomplete data rather than a true reading.
          healthScoreAvailable (a real transaction-count floor, not just isEmpty) covers the
          thin-but-not-zero range isEmpty never did, showing onboarding progress instead of a
          number. */}
      {!isEmpty && (
      <FinoraCard padding="lg" className="mb-6">
        <div className="flex items-center gap-2 mb-4">
          <div className="w-8 h-8 rounded-full bg-primary-light flex items-center justify-center">
            <ShieldCheck size={15} className="text-primary" />
          </div>
          <h2 className="font-semibold text-ink">Financial Health Score</h2>
        </div>
        {summary.healthScoreAvailable ? (
          <div className="grid md:grid-cols-[auto_1fr] gap-6 items-center">
            <div className="text-center md:text-left">
              <p className={`text-4xl font-bold ${healthColor(summary.healthLabel!)}`}>{summary.healthScore}</p>
              <p className="text-xs text-muted">out of 100</p>
              <p className={`text-sm font-medium mt-1 ${healthColor(summary.healthLabel!)}`}>{summary.healthLabel}</p>
            </div>
            <div className="space-y-2.5">
              {Object.entries(summary.healthBreakdown).map(([name, score]) => {
                const detail = summary.healthBreakdownDetail[name];
                const isExpanded = expandedHealthDetail === name;
                return (
                  <div key={name}>
                    <div className="flex justify-between items-baseline mb-1">
                      <span className="text-xs text-ink">
                        {name}
                        {detail && (
                          <button
                            type="button"
                            onClick={() => setExpandedHealthDetail((cur) => (cur === name ? null : name))}
                            aria-expanded={isExpanded}
                            className="ml-1.5 text-primary underline underline-offset-2 font-normal"
                          >
                            {isExpanded ? 'Hide' : 'Why?'}
                          </button>
                        )}
                      </span>
                      <span className="text-xs text-muted">{Math.round(score)}%</span>
                    </div>
                    <div className="h-1.5 bg-bg rounded-full overflow-hidden">
                      <div
                        className={`h-full rounded-full ${healthItemBarColor(score)}`}
                        style={{ width: `${Math.max(0, Math.min(100, score))}%` }}
                      />
                    </div>
                    {detail && isExpanded && (
                      <p className="text-[11px] text-muted mt-1">{detail}</p>
                    )}
                  </div>
                );
              })}
            </div>
          </div>
        ) : (
          <div className="flex flex-col items-center text-center py-4 px-2">
            <div className="w-12 h-12 rounded-full bg-primary-light flex items-center justify-center mb-3">
              <ShieldCheck size={22} className="text-primary" />
            </div>
            <p className="text-sm font-semibold text-ink mb-1">Getting Started</p>
            <p className="text-xs text-muted mb-4 max-w-[240px]">
              Import more transactions to unlock your Financial Health Score.
            </p>
            <div className="w-full max-w-[240px]">
              <div className="flex justify-between text-xs text-muted mb-1.5">
                <span>{summary.healthScoreTransactionCount} / {summary.healthScoreMinTransactions} transactions</span>
                <span>
                  {Math.round(Math.min(100, (summary.healthScoreTransactionCount / summary.healthScoreMinTransactions) * 100))}%
                </span>
              </div>
              <div className="h-1.5 bg-bg rounded-full overflow-hidden">
                <div
                  className="h-full bg-primary rounded-full"
                  style={{ width: `${Math.min(100, (summary.healthScoreTransactionCount / summary.healthScoreMinTransactions) * 100)}%` }}
                />
              </div>
            </div>
          </div>
        )}
      </FinoraCard>
      )}

      {/* Categorization Confidence -- how sure the categorization engine was, on average, about
          the categories it assigned this month. A positive, ongoing data-quality signal, distinct
          from the category-review warning below (which only fires when spend is badly
          miscategorized) -- this can read "Excellent" in the very same month that warning fires,
          if a small number of genuinely low-confidence transactions sit alongside a lot of
          high-confidence ones. Hidden below categorizationConfidenceMinTransactions
          engine-decided transactions this month (server-side floor, same reasoning as
          healthScoreAvailable above): an average of one or two decisions isn't a real reading. */}
      {!isEmpty && summary.categorizationConfidenceScore !== null && (
      <FinoraCard padding="lg" className="mb-6">
        <div className="flex items-center gap-2 mb-3">
          <div className="w-8 h-8 rounded-full bg-primary-light flex items-center justify-center">
            <BadgeCheck size={15} className="text-primary" />
          </div>
          <h2 className="font-semibold text-ink">Categorization Confidence</h2>
        </div>
        <div className="flex items-baseline gap-2">
          <p className={`text-4xl font-bold ${healthColor(scoreLabel(summary.categorizationConfidenceScore))}`}>
            {summary.categorizationConfidenceScore}
          </p>
          <p className="text-xs text-muted">out of 100</p>
        </div>
        <p className={`text-sm font-medium mt-1 ${healthColor(scoreLabel(summary.categorizationConfidenceScore))}`}>
          {scoreLabel(summary.categorizationConfidenceScore)}
        </p>
        <p className="text-xs text-muted mt-2">
          Based on {summary.categorizationConfidenceTransactionCount} automatically categorized transaction
          {summary.categorizationConfidenceTransactionCount === 1 ? '' : 's'} {periodLabel}.
        </p>
      </FinoraCard>
      )}

      {/* Next Actions -- summary.notifications (DashboardService.buildNotifications: credit-card
          payments due soon, low-balance warnings, budget-threshold alerts) has always been
          computed and sent on every dashboard load, but was only ever rendered in TopBar's
          bell-icon dropdown -- easy to miss entirely if a user doesn't happen to open it. This
          surfaces the SAME list, unchanged, directly on the page it's actually about, rather
          than computing anything new. Hidden while isEmpty, same reasoning as Financial Health
          Score above: a brand-new account has nothing computed here to act on yet. */}
      {!isEmpty && (
      <FinoraCard padding="lg" className="mb-6">
        <div className="flex items-center gap-2 mb-4">
          <div className="w-8 h-8 rounded-full bg-primary-light flex items-center justify-center">
            <ListChecks size={15} className="text-primary" />
          </div>
          <h2 className="font-semibold text-ink">Next Actions</h2>
        </div>
        {summary.notifications.length === 0 ? (
          <p className="text-sm text-muted">Nothing needs your attention right now.</p>
        ) : (
          <ul className="space-y-2.5">
            {summary.notifications.map((n, i) => (
              <li key={i} className="flex items-start gap-2.5">
                <AlertTriangle size={14} className="text-warning flex-shrink-0 mt-0.5" />
                <span className="text-sm text-ink">{n}</span>
              </li>
            ))}
          </ul>
        )}
      </FinoraCard>
      )}

      {/* Detected Issues -- ReconciliationService's own duplicate pass already silently excludes
          a row from every total above the moment it runs (Transaction.isDuplicateOf), and until
          now nothing told the user it happened. transactionsApi.confirmNotDuplicate (BH-027,
          "no, these really are two separate transactions") already existed on the backend to let
          a human overrule that guess -- it simply had no caller anywhere in the product. Shown
          only when something was actually flagged (unlike Next Actions above, which stays visible
          with a positive empty state): this is a conditional alert like Limited History and the
          category-review warning, not a standing destination worth checking when empty. */}
      {summary.duplicateTransactionCount > 0 && (
      <FinoraCard padding="lg" className="mb-6">
        <div className="flex items-center gap-2 mb-1">
          <div className="w-8 h-8 rounded-full bg-warning-bg flex items-center justify-center">
            <Copy size={15} className="text-warning" />
          </div>
          <h2 className="font-semibold text-ink">Detected Issues</h2>
        </div>
        <p className="text-xs text-muted mb-4 ml-10">
          {summary.duplicateTransactionCount === 1
            ? "We found 1 transaction that looks like a duplicate and excluded it from your totals."
            : `We found ${summary.duplicateTransactionCount} transactions that look like duplicates and excluded them from your totals.`}
        </p>
        {duplicateConfirmError && (
          <p className="text-danger text-xs mb-3">{duplicateConfirmError}</p>
        )}
        <ul className="divide-y divide-border">
          {summary.detectedDuplicates.map((d) => (
            <li key={d.transactionId} className="flex items-center justify-between gap-3 py-2.5">
              <div className="min-w-0">
                <p className="text-sm text-ink truncate">{d.merchant}</p>
                <p className="text-xs text-muted">
                  {new Date(d.date + 'T00:00:00').toLocaleDateString('en-IN', { day: 'numeric', month: 'short' })} · {fmt(d.amount)}
                </p>
              </div>
              <button
                type="button"
                onClick={() => void handleConfirmNotDuplicate(d.transactionId)}
                disabled={confirmingDuplicateId === d.transactionId}
                className="text-xs text-primary font-medium flex-shrink-0 disabled:opacity-50"
              >
                {confirmingDuplicateId === d.transactionId ? 'Confirming…' : 'Not a duplicate'}
              </button>
            </li>
          ))}
        </ul>
        {summary.duplicateTransactionCount > summary.detectedDuplicates.length && (
          <p className="text-xs text-muted mt-2.5">
            and {summary.duplicateTransactionCount - summary.detectedDuplicates.length} more
          </p>
        )}
      </FinoraCard>
      )}

      {/* D-25 PR3-C. Deliberately NOT gated on isEmpty like Health Score above -- ACCOUNT_CREATED
          is already true the moment a user signs up, so a brand-new account is exactly the case
          this is most useful for. */}
      <FinancialJourney />

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
                    className="inline-flex items-center gap-1.5 bg-primary text-on-primary hover:bg-primary-dark rounded-lg px-4 py-2 text-xs font-semibold"
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
                    {/* Bug 44. categoryEntries can be non-empty with every amount at zero, which
                        the length check above doesn't catch -- totalSpend is then 0 and val /
                        totalSpend is 0/0, rendering the literal string "NaN%". */}
                    <span className="text-muted">{totalSpend > 0 ? ((val / totalSpend) * 100).toFixed(0) : '0'}%</span>
                    <span className="font-medium text-ink">{fmt(val)}</span>
                  </div>
                ))}
              </div>
              {/* Not gated on the "Other" category name -- "Other" is a real, resolvable category
                  (the categorization engine's fallback when nothing matched), so a transaction
                  landing there isn't necessarily uncategorized. categoryReviewWarning instead
                  reuses Transaction.needsCategoryReview, the same per-transaction signal Ledger's
                  "needs review" badge already shows -- flagged only when a default("Other")-
                  sourced guess ALSO misses the user's own confidence threshold. */}
              {summary.categoryReviewWarning && (
                <div className="bg-warning-bg border border-warning/30 rounded-xl2 px-4 py-3 flex items-start gap-2.5 mt-4">
                  <AlertTriangle size={15} className="text-warning flex-shrink-0 mt-0.5" />
                  <div className="flex-1 min-w-0">
                    <p className="text-xs font-medium text-ink">Spending needs category review</p>
                    <p data-testid="category-review-detail" className="text-[11px] text-muted mt-0.5">
                      {`${fmt(summary.categoryReviewSpendAmount)} (${summary.categoryReviewSpendPct.toFixed(0)}%) across ${summary.categoryReviewTransactionCount} transaction${summary.categoryReviewTransactionCount === 1 ? '' : 's'} ${periodLabel} landed in a generic category and could use a closer look.`}
                    </p>
                    <Link to="/app/transactions" className="inline-block mt-2 text-[11px] font-medium text-primary">
                      Review transactions →
                    </Link>
                  </div>
                </div>
              )}
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
            {accountsQ.isLoading ? (
              showAccountsSkeleton && (
                <Skeleton.Region label="Loading accounts">
                  <div className="space-y-3">
                    {[0, 1].map((i) => (
                      <div key={i} className="flex items-center gap-3">
                        <Skeleton.Circle size={36} />
                        <div className="min-w-0 flex-1 space-y-1">
                          <Skeleton.Text width="w-28" />
                          <Skeleton.Text width="w-16" className="h-2.5" />
                        </div>
                        <Skeleton.Text width="w-14" className="h-4" />
                      </div>
                    ))}
                  </div>
                </Skeleton.Region>
              )
            ) : accounts.length === 0 ? (
              <EmptyState
                icon={Wallet}
                iconBg="bg-blue-100"
                iconColor="text-blue-600"
                title="No accounts yet"
                desc="Add your bank accounts to get a complete view."
                cta={
                  <Link to="/app/setup" className="inline-block bg-primary text-on-primary hover:bg-primary-dark rounded-lg px-4 py-2 text-xs font-semibold">
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
                  <Button onClick={() => setShowAddModal(true)}>
                    + Add Transaction
                  </Button>
                }
              />
            ) : recentTxns.map((t) => {
              const cat = categoriesById[t.categoryId];
              const Icon = ICON_COMPONENTS[cat?.icon ?? 'tag'] ?? ShoppingBag;
              const color = t.type === 'INCOME' ? '#16a34a' : (COLOR_HEX[cat?.color ?? 'gray'] ?? '#262A33');
              return (
                <div key={t.id} className="flex items-center gap-3">
                  <div className="w-9 h-9 rounded-full flex items-center justify-center flex-shrink-0" style={{ background: color + '20' }}>
                    <MerchantLogo
                      merchant={t.merchant}
                      size={36}
                      className="rounded-full"
                      fallback={<Icon size={16} style={{ color }} />}
                    />
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
            {budgetsQ.isLoading ? (
              showBudgetsSkeleton && (
                <Skeleton.Region label="Loading budgets">
                  <div className="space-y-4">
                    {[0, 1].map((i) => (
                      <div key={i}>
                        <div className="flex justify-between items-baseline mb-1.5">
                          <Skeleton.Text width="w-20" />
                          <Skeleton.Text width="w-8" className="h-2.5" />
                        </div>
                        <Skeleton.Block className="h-1.5 w-full" />
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
                desc="Create budgets to track your spending and stay on track."
                cta={
                  <Link to="/app/budgets" className="inline-block bg-primary text-on-primary hover:bg-primary-dark rounded-lg px-4 py-2 text-xs font-semibold">
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
            {goalsQ.isLoading ? (
              showGoalsSkeleton && (
                <Skeleton.Region label="Loading goals">
                  <div className="space-y-4">
                    {[0, 1].map((i) => (
                      <div key={i}>
                        <div className="flex justify-between items-baseline mb-1.5">
                          <Skeleton.Text width="w-20" />
                          <Skeleton.Text width="w-8" className="h-2.5" />
                        </div>
                        <Skeleton.Block className="h-1.5 w-full" />
                      </div>
                    ))}
                  </div>
                </Skeleton.Region>
              )
            ) : goals.length === 0 ? (
              <EmptyState
                icon={Target}
                iconBg="bg-red-100"
                iconColor="text-red-600"
                title="No goals yet"
                desc="Set your financial goals and achieve them step by step."
                cta={
                  <Link to="/app/goals" className="inline-block bg-primary text-on-primary hover:bg-primary-dark rounded-lg px-4 py-2 text-xs font-semibold">
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
            <Link to="/app/insights" className="bg-primary text-on-primary text-xs font-semibold rounded-lg px-4 py-2">
              View Insights
            </Link>
          </div>
          {sentences.length === 0 && movers.length === 0 ? (
            <div className="px-6 pb-5">
              <p className="text-xs text-muted mb-3">Get personalized insights to improve your financial health.</p>
              <ul className="space-y-2">
                {[
                  // Bug fix: this used to say "get AI-powered insights", contradicting the honest
                  // "rule-based statistics, not an LLM assistant" framing the Insights page and
                  // mobile screen both state plainly -- someone who only ever saw this Dashboard
                  // card would come away with the wrong idea about what the feature actually is.
                  'Upload or import more transactions to see spending insights.',
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
      <MotionLink
        to="/app/import"
        whileTap={prefersReducedMotion ? undefined : { scale: 0.92 }}
        whileHover={prefersReducedMotion ? undefined : { scale: 1.05 }}
        className="fixed bottom-8 right-8 w-14 h-14 rounded-full bg-primary text-on-primary shadow-soft flex items-center justify-center hover:bg-primary-dark"
        title="Import a bank or credit card statement"
      >
        <Plus size={24} />
      </MotionLink>

      {showAddModal && (
        <AddTransactionModal onClose={() => setShowAddModal(false)} onSaved={onTransactionAdded} />
      )}
    </div>
  );
}

/**
 * Shown while summaryQ/recentTxnsQ (the two structurally-blocking queries -- see the comment
 * above `blockingLoading`) are still in flight. Approximates the real page's shape (greeting, KPI
 * grid, health score block, cash-flow + spending-breakdown row) closely enough that swapping in
 * real content doesn't itself cause a layout jump, without trying to pixel-match every card.
 */
function DashboardSkeleton() {
  return (
    <Skeleton.Region label="Loading your dashboard" className="space-y-6">
      <div>
        <Skeleton.Text width="w-64" className="h-7 mb-2" />
        <Skeleton.Text width="w-96" />
      </div>
      <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-5 gap-4">
        {Array.from({ length: 5 }).map((_, i) => <Skeleton.Card key={i} />)}
      </div>
      <Skeleton.Block className="h-40 rounded-xl2" />
      <div className="grid lg:grid-cols-[1.6fr_1fr] gap-6">
        <Skeleton.Block className="h-72 rounded-xl2" />
        <Skeleton.Block className="h-72 rounded-xl2" />
      </div>
    </Skeleton.Region>
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

