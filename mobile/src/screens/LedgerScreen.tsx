import { useMemo, useState } from 'react';
import {
  ActivityIndicator, Alert, FlatList, Pressable, ScrollView, StyleSheet, Text, TextInput, View,
} from 'react-native';
import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { transactionsApi, type PagedResponse, type TransactionFilters } from '../api/endpoints';
import { SkeletonTransactionRow } from '../components/skeletons/Skeletons';
import { invalidateFinancialData } from '../lib/invalidateFinancialData';
import { toUserMessage } from '../lib/apiError';
import { hapticError, hapticImpact } from '../lib/haptics';
import { useDebouncedValue } from '../lib/useDebouncedValue';
import { useLargeFontScale } from '../lib/useLargeFontScale';
import { fmtCurrency } from '../lib/format';
import { radius, spacing, useTheme } from '../theme';
import type { Transaction } from '../types';

export const LEDGER_PAGE_SIZE = 20;
type TypeFilter = 'ALL' | 'INCOME' | 'EXPENSE';

/**
 * The exact filters this screen's own useInfiniteQuery below sends on a fresh mount (no search
 * keyword typed, no type filter chosen). Exported so Dashboard's prefetch-on-focus hook
 * (usePrefetchAdjacentScreens) can warm ['transactions', DEFAULT_LEDGER_FILTERS] under this EXACT
 * key -- a prefetch built from a near-identical object is a cache miss with extra network calls,
 * not a warm cache.
 */
export const DEFAULT_LEDGER_FILTERS: TransactionFilters = {
  size: LEDGER_PAGE_SIZE,
  sortField: 'date',
  sortDir: 'desc',
};

/** Exported for the same reason as DEFAULT_LEDGER_FILTERS -- prefetchInfiniteQuery needs the
 *  identical pagination cursor logic this screen's own useInfiniteQuery uses below, so a
 *  prefetched page and a screen-fetched page agree on whether there's a next one. */
export function getLedgerNextPageParam(lastPage: PagedResponse<Transaction>) {
  return lastPage.page + 1 < lastPage.totalPages ? lastPage.page + 1 : undefined;
}

export function LedgerScreen() {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const largeText = useLargeFontScale();
  const queryClient = useQueryClient();
  const [keywordInput, setKeywordInput] = useState('');
  const debouncedKeyword = useDebouncedValue(keywordInput, 300);
  const [typeFilter, setTypeFilter] = useState<TypeFilter>('ALL');
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const filters: TransactionFilters = useMemo(
    () => ({
      ...DEFAULT_LEDGER_FILTERS,
      keyword: debouncedKeyword || undefined,
      type: typeFilter === 'ALL' ? undefined : typeFilter,
    }),
    [debouncedKeyword, typeFilter]
  );

  /**
   * Infinite scroll rather than the web's Previous/Next pagination -- the plan's recommended
   * mobile adaptation. This also removes a whole class of bug the web version needs an effect to
   * handle: deleting the last row of the last page can leave the web ledger on an out-of-range
   * page with no way back, so it watches the server's totalPages and clamps. Here there is no
   * current page to strand -- an invalidation just refetches from page 0 forward.
   */
  const {
    data, isLoading, isError, isFetching, isFetchingNextPage, hasNextPage, fetchNextPage, refetch,
  } = useInfiniteQuery({
    queryKey: ['transactions', filters],
    queryFn: ({ pageParam }) => transactionsApi.search({ ...filters, page: pageParam }),
    initialPageParam: 0,
    // The backend's PagedResponse carries a real totalPages, so "is there more" is answered by
    // the server rather than inferred from whether a page came back full.
    getNextPageParam: getLedgerNextPageParam,
  });

  const txns = data?.pages.flatMap((p) => p.content) ?? [];
  const totalElements = data?.pages[0]?.totalElements ?? 0;

  function confirmDelete(t: Transaction) {
    // Before the alert, not after a choice is made -- the same convention iOS's own system apps
    // use for a press that's about to open a destructive confirmation, so the gesture itself
    // feels acknowledged rather than only its eventual outcome.
    hapticImpact();
    // Alert.alert replaces the web's window.confirm(), which doesn't exist in React Native.
    Alert.alert(
      'Delete transaction?',
      `"${t.description || t.merchant}" (${fmtCurrency(t.amount)}) can't be recovered.`,
      [
        { text: 'Cancel', style: 'cancel' },
        { text: 'Delete', style: 'destructive', onPress: () => void handleDelete(t) },
      ]
    );
  }

  async function handleDelete(t: Transaction) {
    setDeletingId(t.id);
    setError(null);
    try {
      await transactionsApi.remove(t.id);
      // Editing/deleting shifts category totals, the account balance, budget progress, goals, and
      // any insight built from spend patterns -- see invalidateFinancialData's own comment.
      invalidateFinancialData(queryClient);
    } catch (e) {
      setError(toUserMessage(e, 'Could not delete this transaction.'));
      hapticError();
    } finally {
      setDeletingId(null);
    }
  }

  return (
    <View style={[styles.flex, { backgroundColor: c.bg, paddingTop: insets.top }]}>
      <View style={styles.header}>
        <Text style={[styles.title, { color: c.ink }]}>Transactions</Text>
        <Text style={[styles.count, { color: c.muted }]}>
          {isLoading ? '' : `${totalElements.toLocaleString('en-IN')} total`}
        </Text>
      </View>

      <TextInput
        value={keywordInput}
        onChangeText={setKeywordInput}
        placeholder="Search description, merchant, bank…"
        placeholderTextColor={c.muted}
        autoCapitalize="none"
        autoCorrect={false}
        accessibilityLabel="Search transactions"
        style={[styles.search, { backgroundColor: c.card, borderColor: c.border, color: c.ink }]}
      />

      <View style={styles.filterRow}>
        {(['ALL', 'INCOME', 'EXPENSE'] as TypeFilter[]).map((t) => (
          <Pressable
            key={t}
            onPress={() => setTypeFilter(t)}
            accessibilityRole="button"
            accessibilityState={{ selected: typeFilter === t }}
            accessibilityLabel={`Filter: ${t === 'ALL' ? 'all' : t.toLowerCase()}`}
            style={[
              styles.chip,
              { borderColor: c.border },
              typeFilter === t && { backgroundColor: c.primaryLight, borderColor: c.primary },
            ]}
          >
            <Text style={[styles.chipText, { color: typeFilter === t ? c.primary : c.muted }]}>
              {t === 'ALL' ? 'All' : t === 'INCOME' ? 'Income' : 'Expense'}
            </Text>
          </Pressable>
        ))}
      </View>

      {error ? <Text style={[styles.error, { color: c.danger }]}>{error}</Text> : null}

      {isLoading ? (
        // ScrollView, not a plain View -- the FlatList is this screen's only other scrollable
        // region and doesn't exist yet during this branch, so a plain View here would silently
        // clip the bottom skeleton rows on shorter-viewport devices once search/filter chrome
        // above eats into the available height.
        <ScrollView contentContainerStyle={styles.listContent}>
          {Array.from({ length: 8 }).map((_, i) => (
            <SkeletonTransactionRow key={i} />
          ))}
        </ScrollView>
      ) : isError && txns.length === 0 ? (
        /**
         * A failed search must not fall through to ListEmptyComponent below. Without this branch
         * `data` is undefined, `txns` is [], and the list renders "No transactions yet. Import a
         * statement to get started." -- which tells someone who may have years of imported history
         * that they have none, and sends them to re-import data they already own. Same class of bug
         * as the dashboard's `!summary` guard: a request that failed is not an answer of zero.
         *
         * Only when there is nothing on screen. A failure while paging is handled in the footer
         * instead, so one bad page cannot blank a list the user is already reading.
         */
        <View style={styles.centered}>
          <Text style={[styles.errorText, { color: c.muted }]}>Couldn't load your transactions.</Text>
          <Pressable onPress={() => void refetch()} hitSlop={12} accessibilityRole="button">
            <Text style={[styles.retry, { color: c.primary }]}>Try again</Text>
          </Pressable>
        </View>
      ) : (
        <FlatList
          testID="ledger-list"
          data={txns}
          keyExtractor={(t) => t.id}
          // Mirrors ImportScreen's own tuning (same three props, same reasoning there). No
          // getItemLayout: row height isn't fixed here -- it varies with description/merchant
          // text length and with the user's font-scale setting (useLargeFontScale above), and a
          // wrong precomputed offset would make FlatList jump to the wrong place on a long list,
          // not just skip the optimization.
          initialNumToRender={12}
          windowSize={9}
          removeClippedSubviews
          onEndReached={() => {
            if (hasNextPage && !isFetchingNextPage) void fetchNextPage();
          }}
          onEndReachedThreshold={0.4}
          refreshing={isFetching && !isFetchingNextPage}
          onRefresh={() => void refetch()}
          contentContainerStyle={styles.listContent}
          ListEmptyComponent={
            <Text style={[styles.empty, { color: c.muted }]}>
              {debouncedKeyword || typeFilter !== 'ALL'
                ? 'No transactions match these filters.'
                : 'No transactions yet. Import a statement to get started.'}
            </Text>
          }
          ListFooterComponent={
            isFetchingNextPage ? (
              <ActivityIndicator style={styles.footer} color={c.primary} />
            ) : isError ? (
              // Reached only with rows already on screen, since the empty case is handled above.
              // Silently stopping here would read as "you have reached the end", so say otherwise
              // and keep the rest of the list usable.
              <View style={styles.footer}>
                <Text style={[styles.errorText, { color: c.muted }]}>
                  Couldn't load more transactions.
                </Text>
                <Pressable onPress={() => void fetchNextPage()} hitSlop={12} accessibilityRole="button">
                  <Text style={[styles.retry, { color: c.primary }]}>Try again</Text>
                </Pressable>
              </View>
            ) : undefined
          }
          renderItem={({ item: t }) => (
            <Pressable
              onLongPress={() => confirmDelete(t)}
              style={[styles.row, { backgroundColor: c.card, borderColor: c.border }]}
              android_ripple={{ color: c.border }}
              // Long-press was the only route to delete, which made it unreachable for anyone
              // using a screen reader -- there's no gesture equivalent in the rotor. Declaring it
              // as an accessibility action exposes it properly, and the hint tells sighted users
              // the gesture exists at all, since nothing on the row advertises it.
              accessibilityRole="button"
              accessibilityLabel={`${t.description || t.merchant || 'Transaction'}, ${
                t.type === 'INCOME' ? 'income' : 'expense'
              } ${fmtCurrency(Math.abs(t.amount))}, ${t.categoryName}, ${t.date}`}
              accessibilityHint="Double tap and hold to delete"
              accessibilityActions={[{ name: 'delete', label: 'Delete transaction' }]}
              onAccessibilityAction={(e) => {
                if (e.nativeEvent.actionName === 'delete') confirmDelete(t);
              }}
            >
              <View style={styles.rowMain}>
                <Text style={[styles.desc, { color: c.ink }]} numberOfLines={largeText ? 2 : 1}>
                  {t.description || t.merchant || 'Transaction'}
                </Text>
                <Text style={[styles.meta, { color: c.mutedInk }]} numberOfLines={1}>
                  {t.categoryName} · {t.date}
                  {t.reconciliationStatus === 'DUPLICATE' ? ' · Duplicate' : ''}
                </Text>
              </View>
              {deletingId === t.id ? (
                <ActivityIndicator size="small" color={c.muted} />
              ) : (
                <Text style={[styles.amount, { color: t.type === 'INCOME' ? c.success : c.ink }]}>
                  {t.type === 'INCOME' ? '+' : '-'}
                  {fmtCurrency(Math.abs(t.amount))}
                </Text>
              )}
            </Pressable>
          )}
        />
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  header: {
    flexDirection: 'row',
    alignItems: 'baseline',
    justifyContent: 'space-between',
    paddingHorizontal: spacing.md,
    paddingTop: spacing.sm,
  },
  title: { fontSize: 22, fontWeight: '700' },
  count: { fontSize: 12 },
  search: {
    marginHorizontal: spacing.md,
    marginTop: spacing.sm,
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: 12,
    paddingVertical: 10,
    fontSize: 14,
  },
  filterRow: {
    flexDirection: 'row',
    gap: spacing.sm,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
  },
  chip: {
    borderWidth: 1,
    borderRadius: 999,
    paddingHorizontal: 16,
    // 44pt is the minimum comfortable touch target on both platforms (Apple HIG and Material
    // both land there). These were ~30pt tall, which is a miss-prone target for a filter people
    // toggle repeatedly while scanning a list.
    minHeight: 44,
    justifyContent: 'center',
  },
  chipText: { fontSize: 12, fontWeight: '600' },
  error: { fontSize: 13, paddingHorizontal: spacing.md, paddingBottom: spacing.sm },
  centered: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  listContent: { paddingHorizontal: spacing.md, paddingBottom: spacing.xl },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    borderWidth: 1,
    borderRadius: radius.md,
    padding: 12,
    marginBottom: spacing.sm,
  },
  rowMain: { flex: 1, marginRight: spacing.sm },
  desc: { fontSize: 14, fontWeight: '500' },
  meta: { fontSize: 11, marginTop: 2 },
  amount: { fontSize: 14, fontWeight: '700' },
  empty: { fontSize: 13, textAlign: 'center', paddingVertical: spacing.xl },
  footer: { paddingVertical: spacing.md, alignItems: 'center', gap: spacing.xs },
  errorText: { fontSize: 14 },
  retry: { fontSize: 14, fontWeight: '600' },
});
