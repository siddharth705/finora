import type { ReactNode } from 'react';
import { StyleSheet, Text, View, type ViewStyle } from 'react-native';
import { radius, spacing, useTheme } from '../theme';

/** The card surface every screen builds on -- the mobile equivalent of the web's
 *  `bg-card rounded-xl2 shadow-card border border-border` combination. */
export function Card({ children, style }: { children: ReactNode; style?: ViewStyle }) {
  const c = useTheme();
  return (
    <View style={[styles.card, { backgroundColor: c.card, borderColor: c.border }, style]}>{children}</View>
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
});
