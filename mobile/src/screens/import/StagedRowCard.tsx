import { memo } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { fmtCurrency } from '../../lib/format';
import type { DuplicateDecision } from '../../lib/importReview';
import { isUnconfirmedGuess, isUnderReview } from '../../lib/importReview';
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
  /** `'unresolved'` while the engine's question is outstanding. Rows never questioned are
   *  `'import'` from the start and render no review block at all. */
  decision: DuplicateDecision;
  onDecide: (decision: DuplicateDecision) => void;
  /** How many OTHER unresolved rows share this description. Zero hides the bulk action, so it
   *  never offers to resolve rows that do not exist. */
  similarUnresolved: number;
  onApplyToSimilar: () => void;
}

function StagedRowCardInner({
  row,
  included,
  category,
  onToggleIncluded,
  onPressCategory,
  decision,
  onDecide,
  similarUnresolved,
  onApplyToSimilar,
}: Props) {
  const c = useTheme();
  const underReview = isUnderReview(row);
  const match = row.duplicateMatch;

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
          {included ? <Text style={[styles.tick, { color: c.onPrimary }]}>✓</Text> : null}
        </Pressable>

        <View style={styles.main}>
          <Text style={[styles.description, { color: c.ink }]} numberOfLines={2}>
            {row.description || 'Transaction'}
          </Text>
          <Text style={[styles.date, { color: c.mutedInk }]}>{row.date}</Text>
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

        {/* The engine says where each suggestion came from; an unconfirmed guess is one it had no
            real evidence for -- "default" (no idea, filed under Other) or a structural
            person-to-person detection. Worth surfacing, because those are the rows actually worth a
            human look. See importReview.isUnconfirmedGuess. */}
        {isUnconfirmedGuess(row.categorySource) ? (
          <Text style={[styles.badge, { color: c.muted, backgroundColor: c.bg }]}>Needs a look</Text>
        ) : null}

        {row.likelyDuplicate ? (
          <Text style={[styles.badge, { color: c.warningInk, backgroundColor: c.warningBg }]}>
            {decision === 'import'
              ? 'Duplicate — importing'
              : decision === 'skip'
                ? 'Duplicate — skipped'
                : 'Possible duplicate'}
          </Text>
        ) : null}
      </View>

      {/* The question, and the evidence for it, in the row it is about. The web app can afford a
          side-by-side comparison; at phone width the answer has to sit where the row is, or the
          user is comparing two things they cannot see at once.

          Rendered only while unresolved: once answered, the badge above carries the outcome and
          re-asking would suggest the answer had not registered. */}
      {underReview && match && decision === 'unresolved' ? (
        <View style={[styles.review, { borderTopColor: c.border }]}>
          <Text style={[styles.reviewReason, { color: c.warningInk }]}>{match.reason}</Text>

          <View style={[styles.matchBox, { backgroundColor: c.bg, borderColor: c.border }]}>
            <Text style={[styles.matchLabel, { color: c.muted }]}>
              {match.matchCount > 1
                ? `Already in your ledger (${match.matchCount} matches)`
                : 'Already in your ledger'}
            </Text>
            <View style={styles.matchLine}>
              <Text style={[styles.matchText, { color: c.ink }]} numberOfLines={1}>
                {match.existingDescription || 'Transaction'}
              </Text>
              <Text style={[styles.matchAmount, { color: c.ink }]}>
                {match.existingType === 'INCOME' ? '+' : '-'}
                {fmtCurrency(Math.abs(match.existingAmount))}
              </Text>
            </View>
            <Text style={[styles.matchMeta, { color: c.mutedInk }]}>{match.existingDate}</Text>
          </View>

          <View style={styles.reviewActions}>
            <Pressable
              onPress={() => onDecide('import')}
              accessibilityRole="button"
              accessibilityLabel={`Import anyway: ${row.description || 'this transaction'}`}
              style={[styles.reviewButton, { borderColor: c.primary }]}
            >
              <Text style={[styles.reviewButtonText, { color: c.primary }]}>Import anyway</Text>
            </Pressable>
            <Pressable
              onPress={() => onDecide('skip')}
              accessibilityRole="button"
              accessibilityLabel={`Skip this row: ${row.description || 'this transaction'}`}
              style={[styles.reviewButton, { borderColor: c.border }]}
            >
              <Text style={[styles.reviewButtonText, { color: c.muted }]}>Skip this row</Text>
            </Pressable>
          </View>
        </View>
      ) : null}

      {/* Offered only after this row is answered, and only while identical rows are still
          outstanding -- it applies THIS answer to those, so it cannot exist before there is one.
          Bounded to unresolved rows by applyDecisionToSimilar, so it never overwrites a choice
          the user already made by hand. */}
      {underReview && decision !== 'unresolved' && similarUnresolved > 0 ? (
        <Pressable
          onPress={onApplyToSimilar}
          accessibilityRole="button"
          accessibilityLabel={`Apply this answer to ${similarUnresolved} identical row${similarUnresolved === 1 ? '' : 's'}`}
          style={[styles.applySimilar, { borderTopColor: c.border }]}
        >
          <Text style={[styles.applySimilarText, { color: c.primary }]}>
            Apply to {similarUnresolved} identical row{similarUnresolved === 1 ? '' : 's'}
          </Text>
        </Pressable>
      ) : null}
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
  tick: { fontSize: 14, fontWeight: '700', lineHeight: 16 },
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
  review: { marginTop: 12, paddingTop: 12, borderTopWidth: 1, gap: 8 },
  reviewReason: { fontSize: 12, fontWeight: '500' },
  matchBox: { borderWidth: 1, borderRadius: radius.md, padding: 10, gap: 4 },
  matchLabel: { fontSize: 10, fontWeight: '600', textTransform: 'uppercase', letterSpacing: 0.4 },
  matchLine: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 8 },
  matchText: { fontSize: 13, fontWeight: '500', flex: 1 },
  matchAmount: { fontSize: 13, fontWeight: '700' },
  matchMeta: { fontSize: 11 },
  reviewActions: { flexDirection: 'row', gap: 8 },
  // 44pt is the Human Interface Guidelines minimum tap target, and these two decide whether a
  // transaction enters someone's ledger -- not a control to make small.
  reviewButton: {
    flex: 1,
    borderWidth: 1,
    borderRadius: radius.md,
    minHeight: 44,
    alignItems: 'center',
    justifyContent: 'center',
  },
  reviewButtonText: { fontSize: 13, fontWeight: '600' },
  applySimilar: {
    marginTop: 10,
    paddingTop: 10,
    borderTopWidth: 1,
    minHeight: 44,
    justifyContent: 'center',
  },
  applySimilarText: { fontSize: 12, fontWeight: '600' },
});
