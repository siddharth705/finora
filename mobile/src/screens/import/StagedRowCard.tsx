import { memo } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { fmtCurrency } from '../../lib/format';
import { radius, spacing, useTheme } from '../../theme';
import type { StagedRow } from '../../types';

/**
 * One staged transaction, awaiting review.
 *
 * The web app reviews these in a wide table with a column per field. That doesn't survive at phone
 * width -- it would either scroll horizontally (so you can't see the amount and the category at
 * once, which is the comparison being made) or shrink the text past readable. A card per row keeps
 * the decision self-contained: what it is, how much, whether it's included, what it'll be filed as.
 *
 * Memoised because a statement can stage hundreds of rows and toggling one must not re-render the
 * rest.
 */
interface Props {
  row: StagedRow;
  included: boolean;
  category: string;
  onToggleIncluded: () => void;
  onPressCategory: () => void;
}

function StagedRowCardInner({ row, included, category, onToggleIncluded, onPressCategory }: Props) {
  const c = useTheme();

  return (
    <View
      style={[
        styles.card,
        { backgroundColor: c.card, borderColor: row.likelyDuplicate ? c.warning : c.border },
        !included && styles.excluded,
      ]}
    >
      <View style={styles.topRow}>
        <Pressable
          onPress={onToggleIncluded}
          hitSlop={8}
          accessibilityRole="checkbox"
          accessibilityState={{ checked: included }}
          accessibilityLabel={`Include ${row.description || 'this transaction'}`}
          style={[
            styles.checkbox,
            { borderColor: included ? c.primary : c.border, backgroundColor: included ? c.primary : 'transparent' },
          ]}
        >
          {included ? <Text style={styles.tick}>✓</Text> : null}
        </Pressable>

        <View style={styles.main}>
          <Text style={[styles.description, { color: c.ink }]} numberOfLines={2}>
            {row.description || 'Transaction'}
          </Text>
          <Text style={[styles.date, { color: c.muted }]}>{row.date}</Text>
        </View>

        <Text style={[styles.amount, { color: row.type === 'INCOME' ? c.success : c.ink }]}>
          {row.type === 'INCOME' ? '+' : '-'}
          {fmtCurrency(Math.abs(row.amount))}
        </Text>
      </View>

      <View style={styles.bottomRow}>
        <Pressable
          onPress={onPressCategory}
          accessibilityRole="button"
          accessibilityLabel={`Category: ${category}. Tap to change.`}
          style={[styles.categoryChip, { borderColor: c.border, backgroundColor: c.primaryLight }]}
        >
          <Text style={[styles.categoryText, { color: c.primary }]} numberOfLines={1}>
            {category}
          </Text>
        </Pressable>

        {/* The engine says where each suggestion came from; "default" means it had no idea and
            filed it under Other rather than inventing a decision. Worth surfacing, because those
            are the rows actually worth a human look. */}
        {row.categorySource === 'default' ? (
          <Text style={[styles.badge, { color: c.muted, backgroundColor: c.bg }]}>Needs a look</Text>
        ) : null}

        {row.likelyDuplicate ? (
          <Text style={[styles.badge, { color: c.warningInk, backgroundColor: c.warningBg }]}>
            Possible duplicate
          </Text>
        ) : null}
      </View>
    </View>
  );
}

export const StagedRowCard = memo(StagedRowCardInner);

const styles = StyleSheet.create({
  card: {
    borderWidth: 1,
    borderRadius: radius.md,
    padding: 12,
    marginBottom: spacing.sm,
  },
  excluded: { opacity: 0.45 },
  topRow: { flexDirection: 'row', alignItems: 'flex-start', gap: 10 },
  checkbox: {
    width: 24,
    height: 24,
    borderRadius: 6,
    borderWidth: 2,
    alignItems: 'center',
    justifyContent: 'center',
    marginTop: 2,
  },
  tick: { color: '#fff', fontSize: 14, fontWeight: '700', lineHeight: 16 },
  main: { flex: 1 },
  description: { fontSize: 14, fontWeight: '500' },
  date: { fontSize: 11, marginTop: 2 },
  amount: { fontSize: 14, fontWeight: '700' },
  bottomRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    marginTop: 10,
    marginLeft: 34,
    flexWrap: 'wrap',
  },
  categoryChip: {
    borderWidth: 1,
    borderRadius: 999,
    paddingHorizontal: 12,
    minHeight: 32,
    justifyContent: 'center',
    maxWidth: '60%',
  },
  categoryText: { fontSize: 12, fontWeight: '600' },
  badge: {
    fontSize: 10,
    fontWeight: '600',
    paddingHorizontal: 8,
    paddingVertical: 4,
    borderRadius: 6,
    overflow: 'hidden',
  },
});
