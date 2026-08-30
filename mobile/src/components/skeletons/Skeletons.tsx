import { StyleSheet, View, type ViewStyle } from 'react-native';
import { radius, spacing, useTheme } from '../../theme';
import { CASHFLOW_HEIGHT, DONUT_SIZE } from '../../lib/chartGeometry';
import { Card } from '../Card';
import { Shimmer } from './Shimmer';

/**
 * First-load placeholders only. Every call site in this app gates these behind `isLoading`
 * (React Query v5 semantics: true only when there is no cached data for that query key yet), never
 * `isFetching` -- so a background refetch never swaps rendered data back out for one of these. See
 * DashboardScreen, LedgerScreen, ReportsScreen, BudgetsScreen and InsightsScreen for the call sites.
 *
 * Every card-shaped skeleton below composes the real `Card` component for its wrapper rather than
 * rebuilding its border/radius/padding by hand -- so a change to Card's own look can't silently
 * drift the skeletons that stand in for it out of sync.
 */

export function SkeletonCard({ lines = 3, style }: { lines?: number; style?: ViewStyle }) {
  return (
    <Card style={style}>
      {/* 15, not an arbitrary guess -- matches SectionHeading's real title fontSize (Card.tsx),
          since this heading placeholder stands in for that text wherever it sits under one. */}
      <Shimmer width="40%" height={15} style={styles.heading} />
      {Array.from({ length: lines }).map((_, i) => (
        <Shimmer key={i} width={i === lines - 1 ? '60%' : '100%'} height={12} style={styles.line} />
      ))}
    </Card>
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
  return (
    <Card testID="skeleton-budget-card">
      <Shimmer width="60%" height={14} style={styles.heading} />
      <Shimmer width="100%" height={8} borderRadius={4} style={styles.line} />
      <Shimmer width="35%" height={11} style={styles.line} />
    </Card>
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
  heading: { marginBottom: spacing.sm },
  line: { marginBottom: 8 },
  txnRow: {
    flexDirection: 'row', alignItems: 'center', paddingVertical: 10, borderBottomWidth: StyleSheet.hairlineWidth,
  },
  txnMain: { flex: 1, marginRight: spacing.sm, gap: 6 },
  donutWrap: { alignItems: 'center', justifyContent: 'center', paddingVertical: spacing.sm },
});
