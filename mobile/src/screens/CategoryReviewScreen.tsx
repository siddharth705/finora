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
  // Rows the user has already resolved this visit, HIDDEN rather than removed from the query
  // cache -- see apply() for why that distinction is the whole fix.
  const [resolvedTxnIds, setResolvedTxnIds] = useState(() => new Set());
  const [resolvedMerchantIds, setResolvedMerchantIds] = useState(() => new Set());

  const singlesQ = useQuery({
    queryKey: ['needs-review'],
    queryFn: () => transactionsApi.needsReview(),
  });
  const groupsQ = useQuery({
    queryKey: ['needs-review-groups'],
    queryFn: () => transactionsApi.needsReviewGroups(),
  });
  const categoriesQ = useQuery({
    queryKey: ['categories'],
    queryFn: () => categoriesApi.list(),
    staleTime: 5 * 60_000, // the category list barely changes within a session
  });
  const categories = categoriesQ.data ?? [];

  const singles = (singlesQ.data ?? []).filter((t) => !resolvedTxnIds.has(t.id));
  const groups = (groupsQ.data ?? []).filter((g) => !resolvedMerchantIds.has(g.merchantId));
  const loading = singlesQ.isLoading || groupsQ.isLoading;
  // Both halves down AND nothing left to show. The `.length === 0` half is not redundant: in
  // TanStack v5 a query KEEPS its data and flips to status 'error' when a *background* refetch
  // fails, so deriving this from isError alone replaced a full, perfectly actionable queue with
  // an error card the moment a refresh blipped -- losing the user's place mid-way through it.
  // LedgerScreen.tsx guards the identical case as `isError && txns.length === 0`; this screen
  // shipped without the second half.
  const failed = singlesQ.isError && groupsQ.isError && singles.length === 0 && groups.length === 0;
  // Exactly one half down. The rows that loaded stay fully usable, but the queue on screen is
  // incomplete and saying nothing about that would quietly understate the user's backlog.
  const partiallyFailed = !failed && (singlesQ.isError || groupsQ.isError);

  async function refresh() {
    setRefreshing(true);
    try {
      // categoriesQ included deliberately: it is the query whose failure makes the picker open
      // with nothing in it, so leaving it out of the recovery path made "Try again" unable to
      // fix the one thing most likely to be broken.
      await Promise.all([singlesQ.refetch(), groupsQ.refetch(), categoriesQ.refetch()]);
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

    // Resolved rows are hidden via `resolved*Ids`, NOT removed with queryClient.setQueryData.
    //
    // The cache-mutating version had a race with its own success path: this screen's queue lives
    // under 'needs-review'/'needs-review-groups', and invalidateFinancialData refetches exactly
    // those keys. Resolve row A, then row B before A's request lands; A succeeds and invalidates;
    // the refetch runs while B's write is still in flight, so the server still reports B as
    // needing review and B pops back onto the screen as uncategorized. The user then re-answers a
    // row they already answered, and the two writes race. The failure rollback made it worse --
    // it re-inserted a row the refetch had already restored, giving two rows with one React key.
    //
    // Hiding sidesteps all of it: the cache stays the server's to own, so a refetch landing at any
    // moment cannot contradict the UI, and rollback is just un-hiding -- which restores the row to
    // its original position for free, with no index arithmetic to get wrong.
    //
    // Still deliberately NOT wrapped in useSingleFlight: each row is its own action, and
    // serializing them would silently discard a correction to row B while row A was in flight.
    // Concurrency is the point here, which is precisely why the race above had to be closed
    // properly rather than by re-serializing.
    if (chosen.kind === 'single') {
      const id = chosen.txn.id;
      setResolvedTxnIds((prev) => new Set(prev).add(id));
      try {
        await transactionsApi.updateCategory(id, categoryName);
        hapticSuccess();
        invalidateFinancialData(queryClient);
      } catch (e) {
        setResolvedTxnIds((prev) => {
          const next = new Set(prev);
          next.delete(id);
          return next;
        });
        setError(toUserMessage(e, 'Could not save that category.'));
        hapticError();
      }
      return;
    }

    const merchantId = chosen.group.merchantId;
    setResolvedMerchantIds((prev) => new Set(prev).add(merchantId));
    try {
      await transactionsApi.bulkRecategorize(chosen.group.transactionIds, categoryName);
      hapticSuccess();
      invalidateFinancialData(queryClient);
    } catch (e) {
      setResolvedMerchantIds((prev) => {
        const next = new Set(prev);
        next.delete(merchantId);
        return next;
      });
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
