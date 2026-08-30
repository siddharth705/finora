import { StyleSheet, View, type ViewStyle } from 'react-native';
import { radius, spacing, useTheme } from '../../theme';
import { CASHFLOW_HEIGHT, DONUT_SIZE } from '../../lib/chartGeometry';
import { Shimmer } from './Shimmer';

/**
 * First-load placeholders only. Every call site in this app gates these behind `isLoading`
 * (React Query v5 semantics: true only when there is no cached data for that query key yet), never
 * `isFetching` -- so a background refetch never swaps rendered data back out for one of these. See
 * DashboardScreen, LedgerScreen, ReportsScreen, BudgetsScreen and InsightsScreen for the call sites.
 */

export function SkeletonCard({ lines = 3, style }: { lines?: number; style?: ViewStyle }) {
  const c = useTheme();
  return (
    <View style={[styles.card, { backgroundColor: c.card, borderColor: c.border }, style]}>
      <Shimmer width="40%" height={13} style={styles.heading} />
      {Array.from({ length: lines }).map((_, i) => (
        <Shimmer key={i} width={i === lines - 1 ? '60%' : '100%'} height={12} style={styles.line} />
      ))}
    </View>
  );
}

/** Mirrors DashboardScreen's and LedgerScreen's own txnRow layout: description + meta on the
 *  left, an amount on the right. */
export function SkeletonTransactionRow() {
  const c = useTheme();
  return (
    <View style={[styles.txnRow, { borderBottomColor: c.border }]} testID="skeleton-transaction-row">
      <View style={styles.txnMain}>
        <Shimmer width="70%" height={14} style={styles.line} />
        <Shimmer width="40%" height={11} />
      </View>
      <Shimmer width={60} height={14} />
    </View>
  );
}

/** Mirrors BudgetsScreen's budget card: a header placeholder standing in for category name +
 *  amounts, a progress-bar-shaped placeholder, and a one-line footer. */
export function SkeletonBudgetCard() {
  const c = useTheme();
  return (
    <View style={[styles.card, { backgroundColor: c.card, borderColor: c.border }]} testID="skeleton-budget-card">
      <Shimmer width="60%" height={14} style={styles.heading} />
      <Shimmer width="100%" height={8} borderRadius={4} style={styles.line} />
      <Shimmer width="35%" height={11} style={styles.line} />
    </View>
  );
}

/** A Card-shaped section with a heading and N transaction-row placeholders -- for any Dashboard
 *  section (Recent Transactions, Goals, Insights) that lists rows once its query resolves. */
export function SkeletonDashboardSection({ rows = 3 }: { rows?: number }) {
  const c = useTheme();
  return (
    <View style={[styles.card, { backgroundColor: c.card, borderColor: c.border }]} testID="skeleton-dashboard-section">
      <Shimmer width="45%" height={15} style={styles.heading} />
      {Array.from({ length: rows }).map((_, i) => (
        <SkeletonTransactionRow key={i} />
      ))}
    </View>
  );
}

/** Matches CashFlowChart's fixed height (CASHFLOW_HEIGHT) and DonutChart's fixed diameter
 *  (DONUT_SIZE) -- see lib/chartGeometry.ts, the single source both real charts already draw from,
 *  so the skeleton never drifts out of sync with the real layout it stands in for. */
export function SkeletonChart({ variant = 'bar', width = 300 }: { variant?: 'bar' | 'donut'; width?: number }) {
  if (variant === 'donut') {
    return (
      <View style={styles.donutWrap} testID="skeleton-chart-donut">
        <Shimmer width={DONUT_SIZE} height={DONUT_SIZE} borderRadius={DONUT_SIZE / 2} />
      </View>
    );
  }
  return <Shimmer testID="skeleton-chart-bar" width={width} height={CASHFLOW_HEIGHT} borderRadius={radius.md} />;
}

const styles = StyleSheet.create({
  card: { borderWidth: 1, borderRadius: radius.lg, padding: spacing.md },
  heading: { marginBottom: spacing.sm },
  line: { marginBottom: 8 },
  txnRow: {
    flexDirection: 'row', alignItems: 'center', paddingVertical: 10, borderBottomWidth: StyleSheet.hairlineWidth,
  },
  txnMain: { flex: 1, marginRight: spacing.sm, gap: 6 },
  donutWrap: { alignItems: 'center', justifyContent: 'center', paddingVertical: spacing.sm },
});
