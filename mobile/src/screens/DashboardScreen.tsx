import { useMemo, useState } from 'react';
import {
  Pressable, RefreshControl, ScrollView, StyleSheet, Text, useWindowDimensions, View,
} from 'react-native';
import { useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigation } from '@react-navigation/native';
import type { BottomTabNavigationProp } from '@react-navigation/bottom-tabs';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { usePreventScreenCapture } from 'expo-screen-capture';
import { AnimatedNumber } from '../components/AnimatedNumber';
import { Card, EmptyState, SectionHeading } from '../components/Card';
import { SkeletonCard, SkeletonChart, SkeletonTransactionRow } from '../components/skeletons/Skeletons';
import { ChecklistWidget } from '../onboarding/ChecklistWidget';
import { DonutChart, type Slice } from '../components/charts/DonutChart';
import { CashFlowChart } from '../components/charts/CashFlowChart';
import {
  accountsApi, dashboardApi, goalsApi, insightsApi, reportsApi, transactionsApi, userApi,
} from '../api/endpoints';
import { useAuth } from '../context/AuthContext';
import { CHART_PALETTE, bucketTopSlices } from '../lib/chartGeometry';
import {
  fmtCurrency, fromLocalDateString, greeting, monthDateRange, monthLabel, monthLabelLong,
} from '../lib/format';
import { invalidateFinancialData } from '../lib/invalidateFinancialData';
import { usePrefetchAdjacentScreens } from '../lib/prefetchAdjacentScreens';
import { deriveRefreshing, isPausedCold } from '../lib/refreshingIndicator';
import { reviewNudgeLabel, reviewQueueCount } from '../lib/reviewQueue';
import { useLargeFontScale } from '../lib/useLargeFontScale';
import { radius, spacing, useTheme, type Palette } from '../theme';
import type { AppTabParamList } from '../navigation/types';

type CashFlowRange = '3M' | '6M' | '12M';
const RANGE_MONTHS: Record<CashFlowRange, number> = { '3M': 3, '6M': 6, '12M': 12 };

/**
 * The label for the remainder bucket, and also a category name the backend really assigns -- which
 * is exactly why it is a named constant: the two have to be compared, not merely both spelled the
 * same way in two places.
 */
const OTHER_LABEL = 'Other';

/**
 * Track C/C1. Same 80/60/40 cutoffs and label vocabulary as frontend/src/pages/Dashboard.tsx's
 * identical helper, for the overall Financial Health Score and label and (via scoreLabel below)
 * Categorization Confidence.
 *
 * `warningInk`, not `warning`, for "Fair": `warning` is tuned for icons/borders/bars (see
 * theme/palette.ts's own comment on why warningInk exists) and falls under WCAG AA as plain text
 * on this screen's background -- warningInk is the token built for exactly that.
 */
function healthColor(label: string, c: Palette): string {
  switch (label) {
    case 'Excellent': return c.success;
    case 'Good': return c.primary;
    case 'Fair': return c.warningInk;
    default: return c.danger;
  }
}

/**
 * Same cutoffs as healthColor, applied to one breakdown row's own score -- ported so a perfect
 * sub-score doesn't inherit the overall label's color (see the web helper's own comment: a
 * Debt Score of 100 rendering as a full red bar because the OVERALL score reads "Needs Attention"
 * is exactly the bug this avoids). A bar fill, not text, so `warning` itself (not `warningInk`) is
 * the right token here.
 */
function healthBarColor(score: number, c: Palette): string {
  if (score >= 80) return c.success;
  if (score >= 60) return c.primary;
  if (score >= 40) return c.warning;
  return c.danger;
}

/** Same 0-100 scale and vocabulary as healthColor above -- Categorization Confidence reuses it
 *  rather than inventing a second one for the same range. */
function scoreLabel(score: number): string {
  if (score >= 80) return 'Excellent';
  if (score >= 60) return 'Good';
  if (score >= 40) return 'Fair';
  return 'Needs Attention';
}

export function DashboardScreen() {
  // SEC-17 (docs/quality/bug-reports/2026-08-19-security-review-findings.md). Balances and
  // account totals render on this screen the moment it mounts -- prevents screenshots/screen
  // recording for as long as it stays mounted, and automatically stops preventing them the
  // instant it unmounts (navigating away, or the app backgrounding through AppLockGate). iOS 13+/
  // all Android versions per expo-screen-capture's own platform notes; older iOS silently does
  // nothing rather than erroring, which is an acceptable degrade, not a broken state.
  usePreventScreenCapture();
  usePrefetchAdjacentScreens();
  const c = useTheme();
  const largeText = useLargeFontScale();
  const insets = useSafeAreaInsets();
  const { width } = useWindowDimensions();
  const { fullName } = useAuth();
  const navigation = useNavigation<BottomTabNavigationProp<AppTabParamList>>();
  const queryClient = useQueryClient();
  const [cashFlowRange, setCashFlowRange] = useState<CashFlowRange>('6M');
  // Which ONE Financial Health Score breakdown row (if any) has its "Why?" detail expanded --
  // one at a time, tracked by name since these rows are rendered inline rather than as their own
  // component. Mirrors frontend/src/pages/Dashboard.tsx.
  const [expandedHealthDetail, setExpandedHealthDetail] = useState<string | null>(null);
  const [confirmingDuplicateId, setConfirmingDuplicateId] = useState<string | null>(null);
  const [duplicateConfirmError, setDuplicateConfirmError] = useState<string | null>(null);

  // useQueries (not one Promise.all) so a single failing endpoint degrades to one empty section
  // instead of blanking the screen -- same reasoning as the web Dashboard's own comment.
  // The accounts query's result is intentionally unbound -- see the comment further down: it
  // fires (and prewarms AccountsScreen's cache) but nothing on this screen reads its data or
  // fetch state anymore.
  const [summaryQ, , recentTxnsQ, goalsQ, insightsQ, settingsQ] = useQueries({
    queries: [
      { queryKey: ['dashboard-summary'], queryFn: () => dashboardApi.summary() },
      { queryKey: ['accounts'], queryFn: () => accountsApi.list() },
      {
        queryKey: ['recent-transactions'],
        queryFn: () => transactionsApi.search({ page: 0, size: 5, sortField: 'date', sortDir: 'desc' }),
      },
      { queryKey: ['goals'], queryFn: () => goalsApi.list() },
      { queryKey: ['insights'], queryFn: () => insightsApi.get(), retry: false },
      { queryKey: ['user-settings'], queryFn: () => userApi.get() },
    ],
  });

  const availableMonthsQ = useQuery({
    queryKey: ['report-months'],
    queryFn: () => reportsApi.availableMonths(),
  });

  // The categorization backlog behind the nudge below. Kept out of the useQueries block above so
  // the destructured indices there stay stable. These two keys never retry -- that policy is set
  // once in api/queryClient.ts rather than here, because per-observer options on a shared key are
  // last-writer-wins across screens (see that file's comment).
  const reviewSinglesQ = useQuery({
    queryKey: ['needs-review'],
    queryFn: () => transactionsApi.needsReview(),
  });
  const reviewGroupsQ = useQuery({
    queryKey: ['needs-review-groups'],
    queryFn: () => transactionsApi.needsReviewGroups(),
  });
  // BOTH halves have to have loaded before the nudge may state a number. The backlog is the sum of
  // two disjoint queries, so if one fails and the other returns rows, `data ?? []` yields a
  // specific, confident, WRONG total -- "2 transactions need a quick look" when the real figure is
  // 12 -- and it is used verbatim as the accessibilityLabel too. Failing silently was always the
  // intent here; that only actually holds when a partial failure suppresses the nudge as well.
  // CategoryReviewScreen (still reachable from More) is where a partial outage gets disclosed.
  const reviewCountKnown = reviewSinglesQ.isSuccess && reviewGroupsQ.isSuccess;
  const reviewCount = reviewQueueCount({
    singles: reviewSinglesQ.data ?? [],
    groups: reviewGroupsQ.data ?? [],
  });
  // Server returns these ascending, so the tail is the most recent N. Depends on
  // availableMonthsQ.data directly, not a `?? []`-derived local -- that fallback would build a new
  // array reference every render while data is still undefined, which defeats this memo entirely.
  const monthsInRange = useMemo(
    () => (availableMonthsQ.data ?? []).slice(-RANGE_MONTHS[cashFlowRange]),
    [availableMonthsQ.data, cashFlowRange]
  );
  const monthlyReportsQ = useQueries({
    queries: monthsInRange.map((month) => ({
      queryKey: ['report', month],
      queryFn: () => reportsApi.forMonth(month),
      staleTime: 5 * 60_000, // a past month's totals don't change once the month is over
    })),
  });
  const cashFlowPoints = useMemo(
    () =>
      monthlyReportsQ
        .map((q) => q.data)
        .filter((d): d is NonNullable<typeof d> => !!d)
        .map((d) => ({ label: monthLabel(d.month), income: d.income, expense: d.expense })),
    [monthlyReportsQ]
  );

  // Cash Flow's own loading/error state, kept separate from the screen-wide gates below because it
  // is fed by its own two-step chain (report-months, then one report query per month in range) that
  // neither `summary` nor initialLoad knows anything about.
  //
  // isPending rather than isLoading: a query that has settled as an error is not pending, so a
  // failure resolves the skeleton instead of leaving it spinning forever, while a background
  // refetch of already-successful data leaves the chart on screen rather than blanking it.
  // isPausedCold is excluded from "settling" deliberately: a query paused for lack of connectivity
  // is pending and will STAY pending until the network returns, so treating it as loading would
  // replace the old false empty state with a skeleton that spins forever. Offline with nothing
  // cached, this card should say it cannot show the chart -- not imply one is on its way.
  const cashFlowSettling =
    (availableMonthsQ.isPending && !isPausedCold(availableMonthsQ)) ||
    monthlyReportsQ.some((q) => q.isPending && !isPausedCold(q));
  // Paused months count as missing for the same reason they are counted as failed: either way the
  // month is absent from an index-based chart that would otherwise close the gap silently.
  const cashFlowMissingMonths = monthlyReportsQ.filter((q) => q.isError || isPausedCold(q)).length;
  // "Genuinely nothing to draw" is monthsInRange being empty -- that is a real answer and must keep
  // reaching CashFlowChart's own empty state. This is the other case: months exist but not one of
  // them could be loaded.
  const cashFlowUnavailable =
    availableMonthsQ.isError ||
    isPausedCold(availableMonthsQ) ||
    (monthsInRange.length > 0 && cashFlowPoints.length === 0);

  // The accounts query's data is never read anywhere on this screen (it only prewarms
  // AccountsScreen's cache), so it stays out of BOTH the initial-load gate (the shell shouldn't
  // wait on a fetch whose result isn't rendered here) and the refreshing indicator below (a pull
  // gesture that visibly finishes shouldn't keep spinning on a fetch the user can't see the result
  // of -- and the reverse bug is just as real: if accounts happens to resolve slower than
  // summary/recent-transactions on first mount, including it here would flip the spinner on with
  // no user gesture at all, since initialLoad has already gone false).
  //
  // Tracks every query whose data IS rendered and that refresh() (below) actually invalidates --
  // summary/recent-transactions plus goals, insights, the available-months list, and the Cash
  // Flow chart's per-month report queries. Missing any of these would let the spinner disappear
  // while a visible section is still silently updating underneath it -- availableMonthsQ itself
  // has to be included too, not just the per-month queries it drives, or a refresh that adds a
  // newly-available month shows nothing happening until that new month's own query mounts a beat
  // later. deriveRefreshing's per-query isLoading gate (not just this initialLoad flag) is what
  // keeps that later-mounted query from flipping the spinner back on with no pull gesture.
  const initialLoad = summaryQ.isLoading || recentTxnsQ.isLoading;
  const refreshing = deriveRefreshing(
    [summaryQ, recentTxnsQ, goalsQ, insightsQ, availableMonthsQ, ...monthlyReportsQ,
     reviewSinglesQ, reviewGroupsQ],
    initialLoad
  );

  function refresh() {
    ['dashboard-summary', 'accounts', 'recent-transactions', 'goals', 'insights', 'report-months',
      'report', 'needs-review', 'needs-review-groups']
      .forEach((key) => void queryClient.invalidateQueries({ queryKey: [key] }));
  }

  // BH-027's own service-layer doc comment: "the user asked for this row to count, so it counts
  // now." transactionsApi.confirmNotDuplicate already existed and already worked on the backend --
  // this is the first mobile UI that calls it. invalidateFinancialData covers dashboard-summary
  // (the card itself), recent-transactions/transactions (the row's own status just changed), and
  // every KPI the reinstated transaction now counts toward.
  async function handleConfirmNotDuplicate(transactionId: string) {
    setConfirmingDuplicateId(transactionId);
    setDuplicateConfirmError(null);
    try {
      await transactionsApi.confirmNotDuplicate(transactionId);
      invalidateFinancialData(queryClient);
    } catch {
      setDuplicateConfirmError("Couldn't update this transaction. Please try again.");
    } finally {
      setConfirmingDuplicateId(null);
    }
  }

  const summary = summaryQ.data;
  const recentTxns = recentTxnsQ.data?.content ?? [];
  // Same signal frontend/src/pages/Dashboard.tsx uses to hide Financial Health Score,
  // Categorization Confidence and Detected Issues below: a score, average, or duplicate flag
  // computed from zero transactions has nothing real behind it.
  const isEmpty = (recentTxnsQ.data?.totalElements ?? 0) === 0;
  const goals = (goalsQ.data ?? []).slice(0, 2);
  const coverageCaveat = insightsQ.data?.coverageCaveat ?? null;
  // The coverage-caveat sentence (Track C/C2) is promoted to its own banner below rather than said
  // twice -- filtered out of the bullet list by the one fixed, always-English substring
  // InsightsService's template ever produces it with ("may be missing"). Safe as an exact
  // correspondence, not a heuristic: that phrase appears in exactly one sentence template, added
  // if and only if coverageCaveat is non-null (see InsightsService.build).
  const sentences = (insightsQ.data?.sentences ?? []).filter((s) => !s.includes('may be missing'));
  const firstName = fullName?.split(' ')[0] ?? 'there';

  /**
   * Every category is accounted for, either as its own slice or inside "Other".
   *
   * This used to take the top six and stop, and the centre label summed only what survived. With
   * seven or more categories that produced two different spend totals on one screen -- the donut
   * saying 34,000 while the Expenses KPI beside it said 35,500 -- and the smaller one carried the
   * authority of sitting inside the chart. The backend builds spendByCategory and monthlyExpense
   * from the same filtered list (DashboardService.java:104), so the full sum IS the period's
   * expense figure; anything less is not a rounding difference, it is wrong.
   *
   * Folding the remainder into a final slice keeps the chart readable without dropping money out
   * of it, so the slices, the centre and the KPI all agree by construction rather than by luck.
   */
  const donutSlices: Slice[] = useMemo(() => {
    if (!summary) return [];
    return bucketTopSlices(Object.entries(summary.spendByCategory), CHART_PALETTE, OTHER_LABEL);
  }, [summary]);

  // summaryQ can fail on its own (the whole point of useQueries above) -- say so rather than
  // rendering a screen of zeroes that reads as "you have no money". Only on a SETTLED failure,
  // though -- summaryQ.isLoading with no cached data yet falls through to the shell below, which
  // shows its own per-section skeletons instead of blocking the whole screen behind one spinner.
  if (!summaryQ.isLoading && !summary) {
    return (
      <View style={[styles.centered, { backgroundColor: c.bg }]}>
        <Text style={[styles.errorText, { color: c.muted }]}>Couldn't load your dashboard.</Text>
        <Pressable onPress={refresh} hitSlop={12} accessibilityRole="button">
          <Text style={[styles.retry, { color: c.primary }]}>Try again</Text>
        </Pressable>
      </View>
    );
  }

  // Bug 05, mobile side. These KPIs are the newest month the account has DATA for, which for a
  // product built around importing statements in arrears is routinely not the current calendar
  // month. This screen asserted "vs last month" over whichever month that happened to be, exactly
  // as the web dashboard did. The backend now says which month it is reporting on; both clients
  // read it rather than guessing, which is the drift check-client-auth-policy.py exists to catch
  // in the auth layer and which this is the reporting-layer instance of.
  // summary can still be undefined here -- a settled failure already returned above, but a still-
  // loading first fetch falls through to the shell, which renders these off default values below.
  const periodIsCurrent = summary ? (summary.reportingMonthIsCurrent || !summary.reportingMonth) : true;
  const periodLabel = periodIsCurrent ? 'this month' : monthLabel(summary!.reportingMonth!);
  const deltaLabel = periodIsCurrent
    ? 'vs last month'
    : `vs the month before ${monthLabel(summary!.reportingMonth!)}`;
  const deltaSpokenLabel = periodIsCurrent
    ? 'versus last month'
    : `versus the month before ${monthLabel(summary!.reportingMonth!)}`;

  const kpis = summary
    ? [
        {
          label: 'Total Balance', value: summary.currentBalance, delta: null as number | null, invert: false,
          // Track C/C5. Total Balance is a STOCK (Account.balance right now), not a flow this
          // reporting period describes, so it has no month-over-month % to put in the same slot
          // the other three KPIs use -- what belongs there instead is when the number was last
          // touched. Account.balance only moves when a transaction posts, so if the newest one on
          // file is from a past month, this figure is only as fresh as that: reuses the exact
          // periodIsCurrent/reportingMonth this screen already computes for the identical reason
          // (Bug 05) rather than inventing a second "how current is this" concept.
          caption: periodIsCurrent ? 'As of today' : `As of ${monthLabel(summary.reportingMonth!)}`,
        },
        { label: 'Income', value: summary.monthlyIncome, delta: summary.incomeDeltaPct, invert: false, caption: null as string | null },
        { label: 'Expenses', value: summary.monthlyExpense, delta: summary.expenseDeltaPct, invert: true, caption: null as string | null },
        { label: 'Net Savings', value: summary.netCashFlow, delta: summary.netDeltaPct, invert: false, caption: null as string | null },
      ]
    : [];

  const chartWidth = width - spacing.md * 2 - spacing.md * 2;

  return (
    <ScrollView
      style={{ backgroundColor: c.bg }}
      contentContainerStyle={[styles.content, { paddingTop: insets.top + spacing.md }]}
      refreshControl={<RefreshControl refreshing={refreshing} onRefresh={refresh} tintColor={c.primary} />}
    >
      <Text style={[styles.greeting, { color: c.ink }]}>
        {greeting(settingsQ.data?.timezone)}, {firstName}
      </Text>
      <Text style={[styles.subGreeting, { color: c.muted }]}>
        Here's what's happening with your finances.
        {!periodIsCurrent && ` Your latest figures are from ${periodLabel}.`}
      </Text>

      <ChecklistWidget />

      {/* Track C/C2. InsightsService has always computed this (aggregated across every live
          account, so it needs no per-account plumbing) and said so as one bullet buried in the
          Insights card below -- easy to miss entirely on a screen full of other numbers. A gap in
          the statement history means every total on this screen may be undercounting the current
          month, which is a data-completeness warning, not a spending observation, so it is
          promoted above everything it could be silently distorting, with a way to actually fix it.
          Ranked above the review-queue nudge below: a category label can wait, a missing
          statement calls the KPIs above into question right now. */}
      {coverageCaveat ? (
        <Pressable
          onPress={() => navigation.navigate('Import')}
          accessibilityRole="button"
          accessibilityLabel={`Possible gap in your ${monthLabelLong(coverageCaveat.month)} history. Import that statement to complete it.`}
          accessibilityHint="Opens the Import screen"
        >
          <Card style={{ ...styles.coverageBanner, backgroundColor: c.warningBg }}>
            <Text style={[styles.coverageBannerTitle, { color: c.warningInk }]}>
              Possible gap in {monthLabelLong(coverageCaveat.month)}
            </Text>
            <Text style={[styles.coverageBannerBody, { color: c.warningInk }]}>
              Some transactions may be missing from your history.
            </Text>
            <Text style={[styles.coverageBannerCta, { color: c.primary }]}>Import that statement ›</Text>
          </Card>
        </Pressable>
      ) : null}

      {/* A count of work, never a chart slice. The categorization design spec (§3) draws a hard
          line here: "Other" is a real category a user chose, while "needs review" is a queue
          state, and rendering the latter as a wedge in the spending donut is an admission of not
          knowing dressed up as information about their money. So it lives here, above the numbers
          it would otherwise quietly distort, as a nudge with somewhere to go. */}
      {reviewCountKnown && reviewCount > 0 ? (
        <Pressable
          onPress={() => navigation.navigate('More', { screen: 'CategoryReview' })}
          accessibilityRole="button"
          accessibilityLabel={reviewNudgeLabel(reviewCount)}
          accessibilityHint="Opens the category review queue"
        >
          <Card style={styles.nudge}>
            <View style={styles.nudgeText}>
              <Text style={[styles.nudgeTitle, { color: c.ink }]}>{reviewNudgeLabel(reviewCount)}</Text>
              <Text style={[styles.nudgeBody, { color: c.muted }]} numberOfLines={2}>
                Label them once and Fynora remembers the merchant for good.
              </Text>
            </View>
            <Text style={[styles.nudgeChevron, { color: c.primary }]} accessibilityElementsHidden importantForAccessibility="no">›</Text>
          </Card>
        </Pressable>
      ) : null}

      <View style={styles.kpiGrid}>
        {summary
          ? kpis.map((k) => (
              <Card key={k.label} style={styles.kpiCard}>
                {/* Grouped into one accessible node: swiping through "Income", "₹82,000", then
                    "▲ 4.1% vs last month" as three separate items loses the connection between
                    them, and the bare triangle is announced as "black up-pointing triangle". */}
                <View
                  accessible
                  accessibilityLabel={
                    k.delta !== null && k.delta !== undefined
                      ? `${k.label}: ${fmtCurrency(k.value)}, ${k.delta >= 0 ? 'up' : 'down'} ${Math.abs(k.delta).toFixed(1)} percent ${deltaSpokenLabel}`
                      : k.caption
                        ? `${k.label}: ${fmtCurrency(k.value)}, ${k.caption}`
                        : `${k.label}: ${fmtCurrency(k.value)}`
                  }
                >
                  <Text style={[styles.kpiLabel, { color: c.muted }]}>{k.label}</Text>
                  {/* AnimatedNumber renders on a non-editable TextInput (see its own doc comment),
                      which has no adjustsFontSizeToFit equivalent -- the auto-shrink this line used
                      to get for an overflowing value is traded for the transition. Accepted
                      deliberately: fmtCurrency rounds to whole rupees and this card has headroom for
                      realistic balances at this font size. Revisit if a real balance is ever reported
                      clipping. numberOfLines={1}'s effect is preserved for free -- a non-multiline
                      TextInput is already single-line. */}
                  <AnimatedNumber
                    testID={`kpi-${k.label}`}
                    value={k.value}
                    style={[styles.kpiValue, { color: c.ink }]}
                  />
                  {k.delta !== null && k.delta !== undefined ? (
                    <Text
                      style={[
                        styles.kpiDelta,
                        { color: (k.invert ? k.delta < 0 : k.delta >= 0) ? c.success : c.danger },
                      ]}
                    >
                      {k.delta >= 0 ? '▲' : '▼'} {Math.abs(k.delta).toFixed(1)}% {deltaLabel}
                    </Text>
                  ) : k.caption ? (
                    <Text style={[styles.kpiDelta, { color: c.mutedInk }]}>{k.caption}</Text>
                  ) : (
                    <Text style={styles.kpiDelta} />
                  )}
                </View>
              </Card>
            ))
          : [0, 1, 2, 3].map((i) => <SkeletonCard key={i} style={styles.kpiCard} lines={1} />)}
      </View>

      {/* Financial Health Score -- DashboardService.computeHealthScore has always returned this
          (score, label, a breakdown), sent on every load; nothing on mobile rendered it until now
          (Track C/C1). Hidden entirely while isEmpty, same reasoning as web: a score computed from
          zero transactions has nothing real behind it. healthScoreAvailable (a real transaction-
          count floor, not just isEmpty) covers the thin-but-not-zero range, showing onboarding
          progress instead of a harsh score. */}
      {!isEmpty && summary ? (
        <Card style={styles.section}>
          <SectionHeading title="Financial Health Score" />
          {summary.healthScoreAvailable ? (
            <View style={styles.healthLayout}>
              <View style={styles.healthScoreBlock}>
                <Text style={[styles.healthScoreValue, { color: healthColor(summary.healthLabel!, c) }]}>
                  {summary.healthScore}
                </Text>
                <Text style={[styles.body, { color: c.muted }]}>out of 100</Text>
                <Text style={[styles.healthScoreLabel, { color: healthColor(summary.healthLabel!, c) }]}>
                  {summary.healthLabel}
                </Text>
              </View>
              <View style={styles.healthBreakdown}>
                {Object.entries(summary.healthBreakdown).map(([name, score]) => {
                  const detail = summary.healthBreakdownDetail[name];
                  const isExpanded = expandedHealthDetail === name;
                  return (
                    <View key={name} style={styles.healthRow}>
                      <View style={styles.healthRowHeader}>
                        <View style={styles.healthRowLabelGroup}>
                          <Text style={[styles.healthRowLabel, { color: c.ink }]}>{name}</Text>
                          {detail ? (
                            <Pressable
                              onPress={() => setExpandedHealthDetail((cur) => (cur === name ? null : name))}
                              hitSlop={8}
                              accessibilityRole="button"
                              accessibilityState={{ expanded: isExpanded }}
                              accessibilityLabel={`${name}: ${isExpanded ? 'hide details' : 'why?'}`}
                            >
                              <Text style={[styles.healthWhy, { color: c.primary }]}>
                                {isExpanded ? 'Hide' : 'Why?'}
                              </Text>
                            </Pressable>
                          ) : null}
                        </View>
                        <Text style={[styles.healthRowScore, { color: c.muted }]}>{Math.round(score)}%</Text>
                      </View>
                      <View style={[styles.progressTrack, { backgroundColor: c.border }]}>
                        <View
                          style={[
                            styles.progressFill,
                            { width: `${Math.max(0, Math.min(100, score))}%`, backgroundColor: healthBarColor(score, c) },
                          ]}
                        />
                      </View>
                      {detail && isExpanded ? (
                        <Text style={[styles.healthDetail, { color: c.muted }]}>{detail}</Text>
                      ) : null}
                    </View>
                  );
                })}
              </View>
            </View>
          ) : (
            <View style={styles.healthGettingStarted}>
              <Text style={[styles.healthGettingStartedTitle, { color: c.ink }]}>Getting Started</Text>
              <Text style={[styles.body, { color: c.muted }]}>
                Import more transactions to unlock your Financial Health Score.
              </Text>
              <View style={styles.healthProgressWrap}>
                <View style={styles.healthProgressLabels}>
                  <Text style={[styles.body, { color: c.muted }]}>
                    {summary.healthScoreTransactionCount} / {summary.healthScoreMinTransactions} transactions
                  </Text>
                  <Text style={[styles.body, { color: c.muted }]}>
                    {Math.round(Math.min(100, (summary.healthScoreTransactionCount / summary.healthScoreMinTransactions) * 100))}%
                  </Text>
                </View>
                <View style={[styles.progressTrack, { backgroundColor: c.border }]}>
                  <View
                    style={[
                      styles.progressFill,
                      {
                        width: `${Math.min(100, (summary.healthScoreTransactionCount / summary.healthScoreMinTransactions) * 100)}%`,
                        backgroundColor: c.primary,
                      },
                    ]}
                  />
                </View>
              </View>
            </View>
          )}
        </Card>
      ) : null}

      {/* Categorization Confidence -- how sure the categorization engine was, on average, about
          the categories it assigned this month. A positive, ongoing data-quality signal, distinct
          from the category-review warning (which only fires when spend is badly miscategorized).
          Hidden below categorizationConfidenceMinTransactions engine-decided transactions this
          month (server-side floor, same reasoning as healthScoreAvailable above). */}
      {!isEmpty && summary && summary.categorizationConfidenceScore !== null ? (
        <Card style={styles.section}>
          <SectionHeading title="Categorization Confidence" />
          <View style={styles.confidenceRow}>
            <Text
              style={[
                styles.healthScoreValue,
                { color: healthColor(scoreLabel(summary.categorizationConfidenceScore), c) },
              ]}
            >
              {summary.categorizationConfidenceScore}
            </Text>
            <Text style={[styles.body, { color: c.muted }]}>out of 100</Text>
          </View>
          <Text
            style={[
              styles.healthScoreLabel,
              { color: healthColor(scoreLabel(summary.categorizationConfidenceScore), c) },
            ]}
          >
            {scoreLabel(summary.categorizationConfidenceScore)}
          </Text>
          <Text style={[styles.body, styles.confidenceCaption, { color: c.muted }]}>
            Based on {summary.categorizationConfidenceTransactionCount} automatically categorized
            transaction{summary.categorizationConfidenceTransactionCount === 1 ? '' : 's'} {periodLabel}.
          </Text>
        </Card>
      ) : null}

      {/* Detected Issues -- ReconciliationService's own duplicate pass already silently excludes a
          row from every total above the moment it runs, and until now nothing told the user it
          happened. transactionsApi.confirmNotDuplicate (BH-027, "no, these really are two separate
          transactions") already existed on the backend to let a human overrule that guess -- this
          is the first mobile UI that calls it. Shown only when something was actually flagged. */}
      {summary && summary.duplicateTransactionCount > 0 ? (
        <Card style={styles.section}>
          <SectionHeading title="Detected Issues" />
          <Text style={[styles.body, { color: c.muted, marginBottom: spacing.sm }]}>
            {summary.duplicateTransactionCount === 1
              ? 'We found 1 transaction that looks like a duplicate and excluded it from your totals.'
              : `We found ${summary.duplicateTransactionCount} transactions that look like duplicates and excluded them from your totals.`}
          </Text>
          {duplicateConfirmError ? (
            <Text style={[styles.body, { color: c.danger, marginBottom: spacing.sm }]}>
              {duplicateConfirmError}
            </Text>
          ) : null}
          {summary.detectedDuplicates.map((d) => (
            <View key={d.transactionId} style={[styles.duplicateRow, { borderBottomColor: c.border }]}>
              <View style={styles.duplicateMain}>
                <Text style={[styles.duplicateMerchant, { color: c.ink }]} numberOfLines={1}>
                  {d.merchant}
                </Text>
                <Text style={[styles.duplicateMeta, { color: c.mutedInk }]}>
                  {fromLocalDateString(d.date).toLocaleDateString('en-IN', { day: 'numeric', month: 'short' })}
                  {' · '}
                  {fmtCurrency(d.amount)}
                </Text>
              </View>
              <Pressable
                onPress={() => void handleConfirmNotDuplicate(d.transactionId)}
                disabled={confirmingDuplicateId === d.transactionId}
                hitSlop={8}
                accessibilityRole="button"
                accessibilityLabel={`Not a duplicate: ${d.merchant}`}
              >
                <Text
                  style={[
                    styles.duplicateAction,
                    { color: c.primary },
                    confirmingDuplicateId === d.transactionId && styles.duplicateActionDisabled,
                  ]}
                >
                  {confirmingDuplicateId === d.transactionId ? 'Confirming…' : 'Not a duplicate'}
                </Text>
              </Pressable>
            </View>
          ))}
          {summary.duplicateTransactionCount > summary.detectedDuplicates.length ? (
            <Text style={[styles.body, { color: c.muted, marginTop: spacing.sm }]}>
              and {summary.duplicateTransactionCount - summary.detectedDuplicates.length} more
            </Text>
          ) : null}
        </Card>
      ) : null}

      <Card style={styles.section}>
        <SectionHeading
          title="Cash Flow"
          action={
            <View style={[styles.rangeRow, { borderColor: c.border }]}>
              {(Object.keys(RANGE_MONTHS) as CashFlowRange[]).map((r) => (
                <Pressable
                  key={r}
                  onPress={() => setCashFlowRange(r)}
                  accessibilityRole="button"
                  accessibilityState={{ selected: cashFlowRange === r }}
                  accessibilityLabel={`Show ${RANGE_MONTHS[r]} months`}
                  style={[styles.rangeChip, cashFlowRange === r && { backgroundColor: c.primaryLight }]}
                >
                  <Text style={[styles.rangeText, { color: cashFlowRange === r ? c.primary : c.muted }]}>{r}</Text>
                </Pressable>
              ))}
            </View>
          }
        />
        {/* Gated on the queries that actually FEED this chart, not on `summary`. Those are
            different requests -- and sequential ones, since the per-month reports can't be issued
            until the months list resolves -- so on any cold start there was a window where
            `summary` had arrived, cashFlowPoints was still [], and CashFlowChart's own empty state
            told a user with years of statements "No monthly data yet."

            The error branches matter for a subtler reason: a dropped month does not leave a gap.
            CashFlowChart's x-axis is index-based, so filtering a failed month out of the series
            re-spaces the survivors and joins two non-adjacent months into one continuous segment --
            the missing month's spike is smoothed away rather than shown as missing, and the range
            chip still claims the full period. Better to say so than to draw a shape that isn't
            true. */}
        {cashFlowSettling ? (
          <SkeletonChart width={chartWidth} />
        ) : cashFlowUnavailable ? (
          <Text style={[styles.errorText, { color: c.danger }]}>Couldn’t load your cash flow.</Text>
        ) : (
          <>
            <CashFlowChart points={cashFlowPoints} width={chartWidth} />
            {cashFlowMissingMonths > 0 ? (
              <Text style={[styles.errorText, { color: c.muted }]}>
                {cashFlowMissingMonths === 1
                  ? 'One month couldn’t be loaded, so it isn’t shown.'
                  : `${cashFlowMissingMonths} months couldn’t be loaded, so they aren’t shown.`}
              </Text>
            ) : null}
          </>
        )}
      </Card>

      <Card style={styles.section}>
        <SectionHeading title="Spending by Category" />
        {summary ? (
          donutSlices.length === 0 ? (
            <EmptyState message={`No spending recorded ${periodLabel} yet.`} />
          ) : (
            <DonutChart
              slices={donutSlices}
              centerLabel={fmtCurrency(donutSlices.reduce((s, x) => s + x.value, 0))}
              onSlicePress={(categoryName) => {
                // reportingMonth can't be null here -- donutSlices is only non-empty when summary
                // has real category spend, which requires a real reporting month behind it.
                const { dateFrom, dateTo } = monthDateRange(summary!.reportingMonth!);
                navigation.navigate('Transactions', {
                  filters: {
                    categoryName, dateFrom, dateTo,
                    label: `${categoryName} · ${monthLabel(summary!.reportingMonth!)}`,
                    nonce: Date.now(),
                  },
                });
              }}
            />
          )
        ) : (
          <SkeletonChart variant="donut" />
        )}
      </Card>

      <Card style={styles.section}>
        <SectionHeading title="Recent Transactions" />
        {recentTxnsQ.isLoading ? (
          <>
            <SkeletonTransactionRow />
            <SkeletonTransactionRow />
            <SkeletonTransactionRow />
          </>
        ) : recentTxnsQ.isError ? (
          // A failed request is not an answer of zero -- same reasoning as LedgerScreen's own
          // isError branch. Without this, a persistent failure here would fall through to the
          // empty-state message below and tell someone with years of history they have none.
          <Text style={[styles.errorText, { color: c.danger }]}>
            Couldn&apos;t load your transactions — pull down to try again.
          </Text>
        ) : recentTxns.length === 0 ? (
          <EmptyState message="No transactions yet. Import a statement to get started." />
        ) : (
          recentTxns.map((t) => (
            <View key={t.id} style={[styles.txnRow, { borderBottomColor: c.border }]}>
              <View style={styles.txnMain}>
                <Text style={[styles.txnDesc, { color: c.ink }]} numberOfLines={largeText ? 2 : 1}>
                  {t.description || t.merchant || 'Transaction'}
                </Text>
                <Text style={[styles.txnMeta, { color: c.mutedInk }]} numberOfLines={1}>
                  {t.categoryName} · {t.date}
                </Text>
              </View>
              <Text style={[styles.txnAmount, { color: t.type === 'INCOME' ? c.success : c.ink }]}>
                {t.type === 'INCOME' ? '+' : '-'}
                {fmtCurrency(Math.abs(t.amount))}
              </Text>
            </View>
          ))
        )}
      </Card>

      {goals.length > 0 ? (
        <Card style={styles.section}>
          <SectionHeading title="Goals" />
          {goals.map((g) => {
            const pct = g.targetAmount > 0 ? Math.min(100, (g.currentAmount / g.targetAmount) * 100) : 0;
            return (
              <View key={g.id} style={styles.goalRow}>
                <View style={styles.goalHeader}>
                  <Text style={[styles.goalName, { color: c.ink }]} numberOfLines={largeText ? 2 : 1}>{g.name}</Text>
                  <Text style={[styles.goalPct, { color: c.mutedInk }]}>{pct.toFixed(0)}%</Text>
                </View>
                <View style={[styles.progressTrack, { backgroundColor: c.border }]}>
                  <View style={[styles.progressFill, { width: `${pct}%`, backgroundColor: c.primary }]} />
                </View>
                <Text style={[styles.goalMeta, { color: c.mutedInk }]}>
                  {fmtCurrency(g.currentAmount)} of {fmtCurrency(g.targetAmount)}
                </Text>
              </View>
            );
          })}
        </Card>
      ) : null}

      {sentences.length > 0 ? (
        <Card style={styles.section}>
          <SectionHeading title="Insights" />
          {sentences.slice(0, 3).map((s) => (
            <Text key={s} style={[styles.insight, { color: c.ink }]}>
              • {s}
            </Text>
          ))}
        </Card>
      ) : null}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  nudge: { flexDirection: 'row', alignItems: 'center', marginBottom: spacing.md },
  nudgeText: { flex: 1, marginRight: spacing.sm },
  nudgeTitle: { fontSize: 14, fontWeight: '600' },
  nudgeBody: { fontSize: 12, marginTop: 2 },
  nudgeChevron: { fontSize: 20, lineHeight: 20 },
  centered: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: spacing.sm },
  errorText: { fontSize: 14 },
  retry: { fontSize: 14, fontWeight: '600' },
  content: { padding: spacing.md, paddingBottom: spacing.xl },
  greeting: { fontSize: 22, fontWeight: '700' },
  subGreeting: { fontSize: 13, marginTop: 2, marginBottom: spacing.md },
  kpiGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm },
  kpiCard: { width: '48%', flexGrow: 1 },
  kpiLabel: { fontSize: 12 },
  kpiValue: { fontSize: 19, fontWeight: '700', marginTop: 4 },
  kpiDelta: { fontSize: 11, marginTop: 2, minHeight: 14 },
  section: { marginTop: spacing.md },
  rangeRow: { flexDirection: 'row', borderWidth: 1, borderRadius: radius.md, overflow: 'hidden' },
  // 44pt minimum touch target -- see the same note in LedgerScreen's filter chips.
  rangeChip: { paddingHorizontal: 14, minHeight: 44, justifyContent: 'center' },
  rangeText: { fontSize: 11, fontWeight: '600' },
  txnRow: { flexDirection: 'row', alignItems: 'center', paddingVertical: 10, borderBottomWidth: StyleSheet.hairlineWidth },
  txnMain: { flex: 1, marginRight: spacing.sm },
  txnDesc: { fontSize: 14, fontWeight: '500' },
  txnMeta: { fontSize: 11, marginTop: 2 },
  txnAmount: { fontSize: 14, fontWeight: '700' },
  goalRow: { marginBottom: spacing.sm },
  goalHeader: { flexDirection: 'row', justifyContent: 'space-between', marginBottom: 4 },
  goalName: { fontSize: 13, fontWeight: '600', flex: 1 },
  goalPct: { fontSize: 12 },
  progressTrack: { height: 6, borderRadius: 3, overflow: 'hidden' },
  progressFill: { height: 6, borderRadius: 3 },
  goalMeta: { fontSize: 11, marginTop: 4 },
  insight: { fontSize: 13, lineHeight: 20, marginBottom: 4 },
  body: { fontSize: 13, lineHeight: 19 },
  // Track C/C1.
  healthLayout: { gap: spacing.md },
  healthScoreBlock: { alignItems: 'flex-start' },
  healthScoreValue: { fontSize: 32, fontWeight: '700' },
  healthScoreLabel: { fontSize: 13, fontWeight: '600', marginTop: 2 },
  healthBreakdown: { gap: spacing.sm },
  healthRow: {},
  healthRowHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 4 },
  healthRowLabelGroup: { flexDirection: 'row', alignItems: 'baseline', gap: 6, flexShrink: 1 },
  healthRowLabel: { fontSize: 12 },
  healthWhy: { fontSize: 12, fontWeight: '600', textDecorationLine: 'underline' },
  healthRowScore: { fontSize: 12 },
  healthDetail: { fontSize: 11, lineHeight: 15, marginTop: 4 },
  healthGettingStarted: { alignItems: 'center', paddingVertical: spacing.sm, gap: 4 },
  healthGettingStartedTitle: { fontSize: 14, fontWeight: '600' },
  healthProgressWrap: { width: '100%', marginTop: spacing.sm, gap: 6 },
  healthProgressLabels: { flexDirection: 'row', justifyContent: 'space-between' },
  confidenceRow: { flexDirection: 'row', alignItems: 'baseline', gap: spacing.xs },
  confidenceCaption: { marginTop: spacing.xs },
  duplicateRow: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    paddingVertical: 10, borderBottomWidth: StyleSheet.hairlineWidth, gap: spacing.sm,
  },
  duplicateMain: { flex: 1 },
  duplicateMerchant: { fontSize: 14 },
  duplicateMeta: { fontSize: 11, marginTop: 2 },
  duplicateAction: { fontSize: 12, fontWeight: '600' },
  duplicateActionDisabled: { opacity: 0.5 },
  // Track C/C2.
  coverageBanner: { marginBottom: spacing.md },
  coverageBannerTitle: { fontSize: 14, fontWeight: '600' },
  coverageBannerBody: { fontSize: 12, marginTop: 2 },
  coverageBannerCta: { fontSize: 12, fontWeight: '600', marginTop: spacing.sm },
});
