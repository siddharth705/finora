import { useMemo, useState } from 'react';
import {
  ActivityIndicator, Pressable, RefreshControl, ScrollView, StyleSheet, Text, useWindowDimensions, View,
} from 'react-native';
import { useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Card, EmptyState, SectionHeading } from '../components/Card';
import { DonutChart, type Slice } from '../components/charts/DonutChart';
import { CashFlowChart } from '../components/charts/CashFlowChart';
import {
  accountsApi, dashboardApi, goalsApi, insightsApi, reportsApi, transactionsApi, userApi,
} from '../api/endpoints';
import { useAuth } from '../context/AuthContext';
import { fmtCurrency, greeting, monthLabel } from '../lib/format';
import { radius, spacing, useTheme } from '../theme';

type CashFlowRange = '3M' | '6M' | '12M';
const RANGE_MONTHS: Record<CashFlowRange, number> = { '3M': 3, '6M': 6, '12M': 12 };

const DONUT_COLORS = ['#3b82f6', '#16a34a', '#f59e0b', '#8b5cf6', '#ef4444', '#94a3b8'];

export function DashboardScreen() {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const { width } = useWindowDimensions();
  const { fullName } = useAuth();
  const queryClient = useQueryClient();
  const [cashFlowRange, setCashFlowRange] = useState<CashFlowRange>('6M');

  // useQueries (not one Promise.all) so a single failing endpoint degrades to one empty section
  // instead of blanking the screen -- same reasoning as the web Dashboard's own comment.
  const [summaryQ, accountsQ, recentTxnsQ, goalsQ, insightsQ, settingsQ] = useQueries({
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

  const { data: availableMonths = [] } = useQuery({
    queryKey: ['report-months'],
    queryFn: () => reportsApi.availableMonths(),
  });
  // Server returns these ascending, so the tail is the most recent N.
  const monthsInRange = useMemo(
    () => availableMonths.slice(-RANGE_MONTHS[cashFlowRange]),
    [availableMonths, cashFlowRange]
  );
  const monthlyReportsQ = useQueries({
    queries: monthsInRange.map((month) => ({
      queryKey: ['report', month],
      queryFn: () => reportsApi.forMonth(month),
      staleTime: 5 * 60_000, // a past month's totals don't change once the month is over
    })),
  });
  const cashFlowPoints = monthlyReportsQ
    .map((q) => q.data)
    .filter((d): d is NonNullable<typeof d> => !!d)
    .map((d) => ({ label: monthLabel(d.month), income: d.income, expense: d.expense }));

  const loading = summaryQ.isLoading || accountsQ.isLoading || recentTxnsQ.isLoading;
  const refreshing = summaryQ.isFetching && !summaryQ.isLoading;

  function refresh() {
    ['dashboard-summary', 'accounts', 'recent-transactions', 'goals', 'insights', 'report-months', 'report']
      .forEach((key) => void queryClient.invalidateQueries({ queryKey: [key] }));
  }

  const summary = summaryQ.data;
  const recentTxns = recentTxnsQ.data?.content ?? [];
  const goals = (goalsQ.data ?? []).slice(0, 2);
  const sentences = insightsQ.data?.sentences ?? [];
  const firstName = fullName?.split(' ')[0] ?? 'there';

  const donutSlices: Slice[] = useMemo(() => {
    if (!summary) return [];
    return Object.entries(summary.spendByCategory)
      .sort((a, b) => b[1] - a[1])
      .slice(0, DONUT_COLORS.length)
      .map(([label, value], i) => ({ label, value, color: DONUT_COLORS[i] }));
  }, [summary]);

  if (loading) {
    return (
      <View style={[styles.centered, { backgroundColor: c.bg }]}>
        <ActivityIndicator size="large" color={c.primary} />
      </View>
    );
  }

  // summaryQ can fail on its own (the whole point of useQueries above) -- say so rather than
  // rendering a screen of zeroes that reads as "you have no money".
  if (!summary) {
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
  const periodIsCurrent = summary.reportingMonthIsCurrent || !summary.reportingMonth;
  const periodLabel = periodIsCurrent ? 'this month' : monthLabel(summary.reportingMonth!);
  const deltaLabel = periodIsCurrent
    ? 'vs last month'
    : `vs the month before ${monthLabel(summary.reportingMonth!)}`;
  const deltaSpokenLabel = periodIsCurrent
    ? 'versus last month'
    : `versus the month before ${monthLabel(summary.reportingMonth!)}`;

  const kpis = [
    { label: 'Total Balance', value: fmtCurrency(summary.currentBalance), delta: null as number | null, invert: false },
    { label: 'Income', value: fmtCurrency(summary.monthlyIncome), delta: summary.incomeDeltaPct, invert: false },
    { label: 'Expenses', value: fmtCurrency(summary.monthlyExpense), delta: summary.expenseDeltaPct, invert: true },
    { label: 'Net Savings', value: fmtCurrency(summary.netCashFlow), delta: summary.netDeltaPct, invert: false },
  ];

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

      <View style={styles.kpiGrid}>
        {kpis.map((k) => (
          <Card key={k.label} style={styles.kpiCard}>
            {/* Grouped into one accessible node: swiping through "Income", "₹82,000", then
                "▲ 4.1% vs last month" as three separate items loses the connection between them,
                and the bare triangle is announced as "black up-pointing triangle". */}
            <View
              accessible
              accessibilityLabel={
                k.delta !== null && k.delta !== undefined
                  ? `${k.label}: ${k.value}, ${k.delta >= 0 ? 'up' : 'down'} ${Math.abs(k.delta).toFixed(1)} percent ${deltaSpokenLabel}`
                  : `${k.label}: ${k.value}`
              }
            >
              <Text style={[styles.kpiLabel, { color: c.muted }]}>{k.label}</Text>
              <Text style={[styles.kpiValue, { color: c.ink }]} numberOfLines={1} adjustsFontSizeToFit>
                {k.value}
              </Text>
              {k.delta !== null && k.delta !== undefined ? (
                <Text
                  style={[
                    styles.kpiDelta,
                    { color: (k.invert ? k.delta < 0 : k.delta >= 0) ? c.success : c.danger },
                  ]}
                >
                  {k.delta >= 0 ? '▲' : '▼'} {Math.abs(k.delta).toFixed(1)}% {deltaLabel}
                </Text>
              ) : (
                <Text style={styles.kpiDelta} />
              )}
            </View>
          </Card>
        ))}
      </View>

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
        <CashFlowChart points={cashFlowPoints} width={chartWidth} />
      </Card>

      <Card style={styles.section}>
        <SectionHeading title="Spending by Category" />
        {donutSlices.length === 0 ? (
          <EmptyState message={`No spending recorded ${periodLabel} yet.`} />
        ) : (
          <DonutChart
            slices={donutSlices}
            centerLabel={fmtCurrency(donutSlices.reduce((s, x) => s + x.value, 0))}
          />
        )}
      </Card>

      <Card style={styles.section}>
        <SectionHeading title="Recent Transactions" />
        {recentTxns.length === 0 ? (
          <EmptyState message="No transactions yet. Import a statement to get started." />
        ) : (
          recentTxns.map((t) => (
            <View key={t.id} style={[styles.txnRow, { borderBottomColor: c.border }]}>
              <View style={styles.txnMain}>
                <Text style={[styles.txnDesc, { color: c.ink }]} numberOfLines={1}>
                  {t.description || t.merchant || 'Transaction'}
                </Text>
                <Text style={[styles.txnMeta, { color: c.muted }]} numberOfLines={1}>
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
                  <Text style={[styles.goalName, { color: c.ink }]} numberOfLines={1}>{g.name}</Text>
                  <Text style={[styles.goalPct, { color: c.muted }]}>{pct.toFixed(0)}%</Text>
                </View>
                <View style={[styles.progressTrack, { backgroundColor: c.border }]}>
                  <View style={[styles.progressFill, { width: `${pct}%`, backgroundColor: c.primary }]} />
                </View>
                <Text style={[styles.goalMeta, { color: c.muted }]}>
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
});
