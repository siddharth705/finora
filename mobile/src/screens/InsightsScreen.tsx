import {
  RefreshControl, ScrollView, StyleSheet, Text, View,
} from 'react-native';
import { useQueries, useQueryClient } from '@tanstack/react-query';
import { Card, EmptyState, SectionHeading } from '../components/Card';
import { SkeletonCard } from '../components/skeletons/Skeletons';
import { insightsApi, recurringApi } from '../api/endpoints';
import { fmtCurrency, fmtDate } from '../lib/format';
import { deriveRefreshing } from '../lib/refreshingIndicator';
import { radius, spacing, useTheme } from '../theme';

/** Port of frontend/src/pages/Insights.tsx. */
export function InsightsScreen() {
  const c = useTheme();
  const queryClient = useQueryClient();

  // useQueries, not Promise.all: the web page loses BOTH sections when either endpoint fails,
  // because one rejected promise fails the pair. Recurring payments and observations are
  // independent, so one being unavailable shouldn't blank the other.
  const [insightsQ, recurringQ] = useQueries({
    queries: [
      { queryKey: ['insights'], queryFn: () => insightsApi.get() },
      { queryKey: ['recurring'], queryFn: () => recurringApi.list() },
    ],
  });

  const loading = insightsQ.isLoading || recurringQ.isLoading;
  const refreshing = deriveRefreshing([insightsQ, recurringQ], loading);
  const sentences = insightsQ.data?.sentences ?? [];
  const recurring = recurringQ.data ?? [];
  const movers = (insightsQ.data?.movers ?? []).filter((m) => m.pctChange !== null).slice(0, 6);

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: ['insights'] });
    void queryClient.invalidateQueries({ queryKey: ['recurring'] });
  }

  return (
    <ScrollView
      style={{ backgroundColor: c.bg }}
      contentContainerStyle={styles.content}
      refreshControl={<RefreshControl refreshing={refreshing} onRefresh={refresh} tintColor={c.primary} />}
    >
      {/* Static -- no data dependency -- so it renders on the very first frame, before either
          query has a chance to resolve. Kept verbatim in spirit from the web page: saying plainly
          that these are rule-based statistics and not an AI assistant is the honest framing, and
          dropping it on mobile would let the same numbers read as something they aren't. */}
      <View style={[styles.notice, { backgroundColor: c.primaryLight, borderLeftColor: c.primary }]}>
        <Text style={[styles.noticeText, { color: c.ink }]}>
          These are rule-based statistical observations from your own transaction history — not an
          AI-generated assistant.
        </Text>
      </View>

      {loading ? (
        <>
          <SkeletonCard style={styles.section} lines={3} />
          <SkeletonCard style={styles.section} lines={4} />
          <SkeletonCard style={styles.section} lines={3} />
        </>
      ) : (
        <>
          <Card style={styles.section}>
            <SectionHeading title="This Month's Observations" />
            {insightsQ.isError ? (
              <Text style={[styles.error, { color: c.danger }]}>
                Couldn&apos;t load your insights — pull down to try again.
              </Text>
            ) : sentences.length === 0 ? (
              <EmptyState message="Nothing stands out this month yet — observations appear as more transactions land." />
            ) : (
              // Keyed by position: these sentences carry no id, the list never reorders or
              // filters, and two identical observations would collide on the text itself.
              sentences.map((s, i) => (
                <View key={i} style={[styles.observation, { borderLeftColor: c.border }]}>
                  <Text style={[styles.observationText, { color: c.ink }]}>{s}</Text>
                </View>
              ))
            )}
          </Card>

          <Card style={styles.section}>
            <SectionHeading title="Recurring Payments & Subscriptions" />
            {recurringQ.isError ? (
              <Text style={[styles.error, { color: c.danger }]}>
                Couldn&apos;t load recurring payments — pull down to try again.
              </Text>
            ) : recurring.length === 0 ? (
              <EmptyState message="No recurring payments detected yet — this needs at least 2 charges from the same merchant on a regular interval to spot a pattern." />
            ) : (
              recurring.map((r) => (
                <View
                  key={r.merchant}
                  style={[styles.row, { borderBottomColor: c.border }]}
                  accessible
                  accessibilityLabel={`${r.merchant}, ${r.label}. ${fmtCurrency(r.averageAmount)} on average, seen ${
                    r.occurrences
                  } times. Next expected around ${fmtDate(r.nextEstimate) ?? r.nextEstimate}`}
                >
                  <View style={styles.rowMain}>
                    <Text style={[styles.rowTitle, { color: c.ink }]} numberOfLines={1}>
                      {r.merchant}
                    </Text>
                    <Text style={[styles.rowMeta, { color: c.mutedInk }]}>
                      {fmtCurrency(r.averageAmount)} · seen {r.occurrences}×
                    </Text>
                  </View>
                  <View style={styles.rowRight}>
                    <Text style={[styles.badge, { color: c.primary, backgroundColor: c.primaryLight }]}>{r.label}</Text>
                    <Text style={[styles.rowMeta, { color: c.mutedInk }]}>next ~{fmtDate(r.nextEstimate) ?? r.nextEstimate}</Text>
                  </View>
                </View>
              ))
            )}
          </Card>

          <Card style={styles.section}>
            <SectionHeading title="Category Movers" />
            {insightsQ.isError ? null : movers.length === 0 ? (
              <EmptyState message="Not enough history yet to compare trends — add a few months of transactions." />
            ) : (
              movers.map((m) => (
                <View
                  key={m.category}
                  style={[styles.row, { borderBottomColor: c.border }]}
                  accessible
                  accessibilityLabel={`${m.category}: ${fmtCurrency(m.current)} versus a usual ${fmtCurrency(
                    m.priorAverage
                  )}, ${(m.pctChange ?? 0) >= 0 ? 'up' : 'down'} ${Math.abs(m.pctChange ?? 0).toFixed(0)} percent`}
                >
                  <View style={styles.rowMain}>
                    <Text style={[styles.rowTitle, { color: c.ink }]} numberOfLines={1}>
                      {m.category}
                    </Text>
                    <Text style={[styles.rowMeta, { color: c.mutedInk }]}>
                      {fmtCurrency(m.current)} vs usual {fmtCurrency(m.priorAverage)}
                    </Text>
                  </View>
                  {/* Spending more is the bad direction here, so up is danger -- the inverse of
                      the Dashboard's income KPI. Same convention as the web page. */}
                  <Text style={[styles.delta, { color: (m.pctChange ?? 0) >= 0 ? c.danger : c.success }]}>
                    {(m.pctChange ?? 0) >= 0 ? '▲' : '▼'} {Math.abs(m.pctChange ?? 0).toFixed(0)}%
                  </Text>
                </View>
              ))
            )}
          </Card>
        </>
      )}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  content: { padding: spacing.md, paddingBottom: spacing.xl },
  notice: {
    borderLeftWidth: 3,
    borderRadius: radius.md,
    padding: spacing.sm,
  },
  noticeText: { fontSize: 12, lineHeight: 18 },
  section: { marginTop: spacing.md },
  error: { fontSize: 13, paddingVertical: spacing.sm },
  observation: {
    borderLeftWidth: 3,
    paddingLeft: spacing.sm,
    paddingVertical: 6,
    marginBottom: spacing.sm,
  },
  observationText: { fontSize: 13, lineHeight: 20 },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: 10,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  rowMain: { flex: 1, marginRight: spacing.sm },
  rowRight: { alignItems: 'flex-end', gap: 4 },
  rowTitle: { fontSize: 14, fontWeight: '500', textTransform: 'capitalize' },
  rowMeta: { fontSize: 11, marginTop: 2 },
  badge: {
    fontSize: 10,
    fontWeight: '600',
    paddingHorizontal: 8,
    paddingVertical: 3,
    borderRadius: radius.md,
    overflow: 'hidden',
    textTransform: 'uppercase',
  },
  delta: { fontSize: 13, fontWeight: '600' },
});
