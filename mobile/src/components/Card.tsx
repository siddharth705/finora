import type { ReactNode } from 'react';
import { StyleSheet, Text, View, type ViewStyle } from 'react-native';
import { radius, spacing, useTheme } from '../theme';

/** The card surface every screen builds on -- the mobile equivalent of the web's
 *  `bg-card rounded-xl2 shadow-card border border-border` combination. */
export function Card({ children, style, testID }: { children: ReactNode; style?: ViewStyle; testID?: string }) {
  const c = useTheme();
  return (
    <View testID={testID} style={[styles.card, { backgroundColor: c.card, borderColor: c.border }, style]}>
      {children}
    </View>
  );
}

export function SectionHeading({ title, action }: { title: string; action?: ReactNode }) {
  const c = useTheme();
  return (
    <View style={styles.headingRow}>
      <Text style={[styles.heading, { color: c.ink }]}>{title}</Text>
      {action}
    </View>
  );
}

/** Shown wherever a list has nothing in it yet -- states the reason plainly rather than
 *  rendering an empty box the user has to interpret. */
export function EmptyState({ message }: { message: string }) {
  const c = useTheme();
  return <Text style={[styles.empty, { color: c.muted }]}>{message}</Text>;
}

/** A label/value pair inside a details grid -- wrap a row of these in a `View` styled
 *  `{ flexDirection: 'row', flexWrap: 'wrap' }` for the two-per-row layout StatementHistoryScreen
 *  and TransactionSourceModal both use. Previously two nearly-identical private copies of this
 *  same component (and its styles) lived in those two files. */
export function DetailField({ label, value }: { label: string; value: string }) {
  const c = useTheme();
  return (
    <View style={styles.detailField}>
      <Text style={[styles.detailFieldLabel, { color: c.muted }]}>{label}</Text>
      <Text style={[styles.detailFieldValue, { color: c.ink }]}>{value}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  card: {
    borderWidth: 1,
    borderRadius: radius.lg,
    padding: spacing.md,
  },
  headingRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: spacing.sm,
  },
  heading: {
    fontSize: 15,
    fontWeight: '700',
  },
  empty: {
    fontSize: 13,
    paddingVertical: spacing.md,
    textAlign: 'center',
  },
  detailField: { width: '50%', paddingVertical: 6, paddingRight: spacing.sm },
  detailFieldLabel: { fontSize: 12, fontWeight: '500', marginBottom: 4 },
  detailFieldValue: { fontSize: 13, lineHeight: 18 },
});
