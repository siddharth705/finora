import { useState } from 'react';
import {
  ActivityIndicator, Pressable, RefreshControl, ScrollView, StyleSheet, Text, View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Card, EmptyState, SectionHeading } from '../components/Card';
import { OptionPickerModal } from '../components/OptionPickerModal';
import { SkeletonTransactionRow } from '../components/skeletons/Skeletons';
import { categoriesApi, transactionsApi } from '../api/endpoints';
import { toUserMessage } from '../lib/apiError';
import { fmtCurrency } from '../lib/format';
import { hapticError, hapticSuccess } from '../lib/haptics';
import { invalidateFinancialData } from '../lib/invalidateFinancialData';
import { reinsertAt } from '../lib/reviewQueue';
import { spacing, useTheme } from '../theme';
import type { MerchantGroup, Transaction } from '../types';

/**
 * The categorization correction loop — the mobile half of "Ask Once, Learn Forever".
 *
 * Mobile shipped `needsReview`/`updateCategory`/`bulkRecategorize` in its API layer with zero
 * callers, and Settings promised a queue ("anything below it is left for you to confirm") that had
 * nowhere to lead. This is that queue.
 *
 * Two sections, because the server splits the backlog into two disjoint halves and rendering only
 * one strands the other (see `lib/reviewQueue.ts`). Merchants come first deliberately: the design
 * spec calls merchant review — not transaction review — the primary interaction, ordered by how
 * many transactions each correction clears, which is exactly the order the server already returns
 * groups in. Labelling "Swiggy" once should clear five rows, and be the first thing offered.
 *
 * Not a port of the web's two Dashboard cards. Those use a select-then-confirm pair per row, which
 * costs two taps and a lot of vertical space on a phone; here the picker sheet *is* the
 * confirmation — you cannot open it by accident and you cannot leave it having chosen nothing.
 */
export function CategoryReviewScreen() {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const queryClient = useQueryClient();

  const [target, setTarget] = useState<PickerTarget | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);

  const singlesQ = useQuery({
    queryKey: ['needs-review'],
    queryFn: () => transactionsApi.needsReview(),
  });
  const groupsQ = useQuery({
    queryKey: ['needs-review-groups'],
    queryFn: () => transactionsApi.needsReviewGroups(),
  });
  const { data: categories = [] } = useQuery({
    queryKey: ['categories'],
    queryFn: () => categoriesApi.list(),
    staleTime: 5 * 60_000, // the category list barely changes within a session
  });

  const singles = singlesQ.data ?? [];
  const groups = groupsQ.data ?? [];
  const loading = singlesQ.isLoading || groupsQ.isLoading;
  // Only a total failure is worth a banner: if one half loaded, its rows are still fully
  // actionable, and blanking the screen over the other half would strand work the user can do.
  const failed = singlesQ.isError && groupsQ.isError;
  // Exactly one half down. The rows that loaded stay fully usable, but the queue on screen is
  // incomplete and saying nothing about that would quietly understate the user's backlog.
  const partiallyFailed = !failed && (singlesQ.isError || groupsQ.isError);

  async function refresh() {
    setRefreshing(true);
    try {
      await Promise.all([singlesQ.refetch(), groupsQ.refetch()]);
    } finally {
      setRefreshing(false);
    }
  }

  /**
   * Apply a category, removing the row optimistically.
   *
   * The queue exists to be worked through quickly, so waiting on each round trip before the row
   * clears is the whole cost of doing this one-by-one. Correctness still lives in the response:
   * both failure paths below put the row back exactly where it was, and the cache invalidation on
   * success replaces the guess with the server's real answer.
   */
  async function apply(categoryName: string) {
    if (!target) return;
    const chosen = target;
    setTarget(null);
    setError(null);

    // Deliberately NOT wrapped in useSingleFlight, unlike the write paths on Goals/Budgets.
    // That guard serializes ALL calls through one ref, which is right when a screen has a single
    // submit button and a second press means "the same save, twice". Here every row is a
    // different action, so on a slow connection it would silently discard the user's correction
    // to row B because row A's request hadn't landed yet -- in a queue whose entire purpose is
    // working through rows quickly. The double-submit it would protect against is harmless
    // anyway: both writes go through PATCH/bulk-category, which set an explicit category rather
    // than mutating a running value, so applying the same one twice is indistinguishable from
    // applying it once. The row also vanishes optimistically on the first press.
    if (chosen.kind === 'single') {
      const index = singles.findIndex((t) => t.id === chosen.txn.id);
      if (index === -1) return;
      queryClient.setQueryData<Transaction[]>(['needs-review'], (prev) =>
        (prev ?? []).filter((t) => t.id !== chosen.txn.id));
      try {
        await transactionsApi.updateCategory(chosen.txn.id, categoryName);
        hapticSuccess();
        invalidateFinancialData(queryClient);
      } catch (e) {
        queryClient.setQueryData<Transaction[]>(['needs-review'], (prev) =>
          reinsertAt(prev ?? [], index, chosen.txn));
        setError(toUserMessage(e, 'Could not save that category.'));
        hapticError();
      }
      return;
    }

    const index = groups.findIndex((g) => g.merchantId === chosen.group.merchantId);
    if (index === -1) return;
    queryClient.setQueryData<MerchantGroup[]>(['needs-review-groups'], (prev) =>
      (prev ?? []).filter((g) => g.merchantId !== chosen.group.merchantId));
    try {
      await transactionsApi.bulkRecategorize(chosen.group.transactionIds, categoryName);
      hapticSuccess();
      invalidateFinancialData(queryClient);
    } catch (e) {
      queryClient.setQueryData<MerchantGroup[]>(['needs-review-groups'], (prev) =>
        reinsertAt(prev ?? [], index, chosen.group));
      setError(toUserMessage(e, 'Could not apply that category.'));
      hapticError();
    }
  }

  // Both halves must have SUCCEEDED before the app tells someone their queue is clear. Deriving
  // this from "no rows and not fully failed" instead conflates a failure with a genuine zero --
  // one half erroring while the other returned nothing would congratulate the user for finishing
  // work they can't currently see. That is the same class of bug LedgerScreen.test.tsx exists to
  // pin ("a failed request and an empty one do not render the same thing"), reached from a
  // different direction.
  const empty = singlesQ.isSuccess && groupsQ.isSuccess && singles.length === 0 && groups.length === 0;

  return (
    <ScrollView
      style={{ backgroundColor: c.bg }}
      contentContainerStyle={[styles.content, { paddingTop: insets.top + spacing.md }]}
      refreshControl={<RefreshControl refreshing={refreshing} onRefresh={refresh} tintColor={c.primary} />}
    >
      <Text style={[styles.title, { color: c.ink }]}>Review categories</Text>
      <Text style={[styles.subtitle, { color: c.muted }]}>
        Pick a category once — Fynora remembers it for every future transaction from the same
        merchant.
      </Text>

      {error ? <Text style={[styles.error, { color: c.danger }]}>{error}</Text> : null}
      {partiallyFailed ? (
        <Text style={[styles.warning, { color: c.mutedInk }]}>
          Part of your review queue couldn’t be loaded. Pull down to try again.
        </Text>
      ) : null}

      {loading ? (
        <Card style={styles.section}>
          <SkeletonTransactionRow />
          <SkeletonTransactionRow />
          <SkeletonTransactionRow />
        </Card>
      ) : failed ? (
        <Card style={styles.section}>
          <Text style={[styles.error, { color: c.danger }]}>Couldn’t load your review queue.</Text>
          <Pressable onPress={refresh} hitSlop={12} accessibilityRole="button">
            <Text style={[styles.retry, { color: c.primary }]}>Try again</Text>
          </Pressable>
        </Card>
      ) : empty ? (
        <Card style={styles.section}>
          <EmptyState message="Nothing to review — every transaction has a category." />
        </Card>
      ) : (
        <>
          {groups.length > 0 ? (
            <Card style={styles.section}>
              <SectionHeading title="Categorize a whole merchant" />
              <Text style={[styles.sectionHint, { color: c.muted }]}>
                One choice covers every transaction from that merchant.
              </Text>
              {groups.map((g) => (
                <Pressable
                  key={g.merchantId}
                  onPress={() => setTarget({ kind: 'group', group: g })}
                  style={[styles.row, { borderBottomColor: c.border }]}
                  android_ripple={{ color: c.border }}
                  accessibilityRole="button"
                  accessibilityLabel={`${g.merchantName}, ${g.transactionIds.length} transactions`}
                  accessibilityHint="Opens the category picker for every transaction from this merchant"
                >
                  <View style={styles.rowMain}>
                    <Text style={[styles.rowTitle, { color: c.ink }]} numberOfLines={1}>
                      {g.merchantName}
                    </Text>
                    <Text style={[styles.rowMeta, { color: c.mutedInk }]}>
                      {g.transactionIds.length} transactions
                    </Text>
                  </View>
                  <Text style={[styles.choose, { color: c.primary }]}>Choose</Text>
                </Pressable>
              ))}
            </Card>
          ) : null}

          {singles.length > 0 ? (
            <Card style={styles.section}>
              <SectionHeading title="One-off transactions" />
              <Text style={[styles.sectionHint, { color: c.muted }]}>
                These don’t share a merchant with anything else waiting.
              </Text>
              {singles.map((t) => (
                <Pressable
                  key={t.id}
                  onPress={() => setTarget({ kind: 'single', txn: t })}
                  style={[styles.row, { borderBottomColor: c.border }]}
                  android_ripple={{ color: c.border }}
                  accessibilityRole="button"
                  accessibilityLabel={`${t.description || t.merchant || 'Transaction'}, ${fmtCurrency(
                    Math.abs(t.amount)
                  )}, ${t.date}`}
                  accessibilityHint="Opens the category picker for this transaction"
                >
                  <View style={styles.rowMain}>
                    <Text style={[styles.rowTitle, { color: c.ink }]} numberOfLines={1}>
                      {t.description || t.merchant || 'Transaction'}
                    </Text>
                    <Text style={[styles.rowMeta, { color: c.mutedInk }]} numberOfLines={1}>
                      {t.date} · {fmtCurrency(Math.abs(t.amount))}
                    </Text>
                  </View>
                  <Text style={[styles.choose, { color: c.primary }]}>Choose</Text>
                </Pressable>
              ))}
            </Card>
          ) : null}
        </>
      )}

      {/* Rendered unconditionally so the sheet's slide-out animation isn't cut off by the row
          that opened it disappearing optimistically the moment a category is picked. */}
      <OptionPickerModal
        visible={target !== null}
        title={pickerTitle(target)}
        options={categories.map((x) => x.name)}
        selected={null}
        onSelect={(name) => void apply(name)}
        onClose={() => setTarget(null)}
      />

      {singlesQ.isFetching || groupsQ.isFetching ? (
        <View style={styles.footerSpinner}>
          <ActivityIndicator size="small" color={c.muted} />
        </View>
      ) : null}
    </ScrollView>
  );
}

type PickerTarget =
  | { kind: 'single'; txn: Transaction }
  | { kind: 'group'; group: MerchantGroup };

/**
 * Names the stakes in the sheet's own header, because on mobile the row that explains them is
 * behind the sheet: "Apply to 5 Swiggy transactions" is a materially different action from
 * categorizing one row, and the user should see which one they're confirming.
 */
function pickerTitle(target: PickerTarget | null): string {
  if (target?.kind === 'group') {
    const n = target.group.transactionIds.length;
    return `Apply to ${n} ${n === 1 ? 'transaction' : 'transactions'}`;
  }
  return 'Choose a category';
}

const styles = StyleSheet.create({
  content: { padding: spacing.md, paddingBottom: spacing.xl },
  title: { fontSize: 22, fontWeight: '700' },
  subtitle: { fontSize: 13, marginTop: 4, marginBottom: spacing.md },
  section: { marginBottom: spacing.md },
  sectionHint: { fontSize: 12, marginBottom: spacing.sm },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: 12,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  rowMain: { flex: 1, marginRight: spacing.sm },
  rowTitle: { fontSize: 14, fontWeight: '600' },
  rowMeta: { fontSize: 12, marginTop: 2 },
  choose: { fontSize: 13, fontWeight: '600' },
  error: { fontSize: 13, marginBottom: spacing.sm },
  warning: { fontSize: 12, marginBottom: spacing.sm },
  retry: { fontSize: 13, fontWeight: '600' },
  footerSpinner: { alignItems: 'center', paddingVertical: spacing.sm },
});
