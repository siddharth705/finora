import { StyleSheet, View, type ViewStyle } from 'react-native';
import { radius, spacing, useTheme } from '../../theme';
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

const styles = StyleSheet.create({
  card: { borderWidth: 1, borderRadius: radius.lg, padding: spacing.md },
  heading: { marginBottom: spacing.sm },
  line: { marginBottom: 8 },
  txnRow: {
    flexDirection: 'row', alignItems: 'center', paddingVertical: 10, borderBottomWidth: StyleSheet.hairlineWidth,
  },
  txnMain: { flex: 1, marginRight: spacing.sm, gap: 6 },
});
