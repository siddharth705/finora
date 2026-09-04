import { useState } from 'react';
import { ActivityIndicator, FlatList, Pressable, RefreshControl, StyleSheet, Text, View } from 'react-native';
import { useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import Ionicons from '@expo/vector-icons/Ionicons';
import { NewTicketSheet } from './support/NewTicketSheet';
import { supportApi, type SupportTicketCategory, type SupportTicketStatus } from '../api/endpoints';
import { fmtDate } from '../lib/format';
import { radius, spacing, useTheme } from '../theme';
import type { MoreStackParamList } from '../navigation/types';

const CATEGORY_LABELS: Record<SupportTicketCategory, string> = {
  STATEMENT_IMPORT: 'Statement import',
  CATEGORIZATION: 'Categorization',
  ACCOUNT_LINKING: 'Account linking',
  DATA_ACCURACY: 'Data accuracy',
  TECHNICAL_ISSUE: 'Technical issue',
  OTHER: 'Other',
};

/** Not a theme-driven tone (see frontend's identical comment on this same table): four visually
 *  distinct states an "open vs. resolved" ticket list has to read at a glance. */
const STATUS_STYLE: Record<SupportTicketStatus, { label: string; bg: (c: ReturnType<typeof useTheme>) => string; fg: (c: ReturnType<typeof useTheme>) => string }> = {
  OPEN: { label: 'Open', bg: (c) => c.primaryLight, fg: (c) => c.primary },
  IN_PROGRESS: { label: 'In Progress', bg: (c) => c.warningBg, fg: (c) => c.warningInk },
  RESOLVED: { label: 'Resolved', bg: (c) => c.successBg, fg: (c) => c.success },
  CLOSED: { label: 'Closed', bg: (c) => c.bg, fg: (c) => c.muted },
};

/** Support, Help & Feedback v1, Phase 8 (mobile). "My Tickets" -- ported from
 *  frontend/src/pages/SupportTickets.tsx. Reached from Settings' "Help & Support" section. */
export function SupportTicketsScreen() {
  const c = useTheme();
  const navigation = useNavigation<NativeStackNavigationProp<MoreStackParamList>>();
  const queryClient = useQueryClient();
  const [showNew, setShowNew] = useState(false);

  const ticketsQuery = useQuery({
    queryKey: ['support-tickets-mine'],
    queryFn: () => supportApi.list(0, 50),
  });

  const tickets = ticketsQuery.data?.content ?? [];

  return (
    <View style={[styles.flex, { backgroundColor: c.bg }]}>
      <FlatList
        data={tickets}
        keyExtractor={(t) => t.id}
        contentContainerStyle={styles.content}
        refreshControl={
          <RefreshControl
            refreshing={ticketsQuery.isFetching && !ticketsQuery.isLoading}
            onRefresh={() => void ticketsQuery.refetch()}
            tintColor={c.primary}
          />
        }
        ListHeaderComponent={
          <View style={styles.header}>
            <View style={styles.headerText}>
              <Text style={[styles.title, { color: c.ink }]}>My Tickets</Text>
              <Text style={[styles.subtitle, { color: c.muted }]}>Support requests you&apos;ve filed with Fynora.</Text>
            </View>
            <Pressable
              onPress={() => setShowNew(true)}
              style={[styles.newButton, { backgroundColor: c.primary }]}
              accessibilityRole="button"
              accessibilityLabel="New ticket"
            >
              <Ionicons name="add" size={16} color={c.onPrimary} />
              <Text style={[styles.newButtonText, { color: c.onPrimary }]}>New Ticket</Text>
            </Pressable>
          </View>
        }
        ListEmptyComponent={
          ticketsQuery.isLoading ? (
            <ActivityIndicator size="large" color={c.primary} style={styles.centered} />
          ) : ticketsQuery.isError ? (
            // Distinct from "no tickets" on purpose -- see frontend's identical fix and its own
            // comment for why collapsing the two is a real bug, not a cosmetic one.
            <View style={styles.centered}>
              <Text style={[styles.emptyText, { color: c.danger }]}>Couldn&apos;t load your tickets.</Text>
              <Pressable onPress={() => void ticketsQuery.refetch()} accessibilityRole="button">
                <Text style={[styles.retryText, { color: c.primary }]}>Try again</Text>
              </Pressable>
            </View>
          ) : (
            <View style={styles.centered}>
              <Ionicons name="help-buoy-outline" size={28} color={c.muted} />
              <Text style={[styles.emptyText, { color: c.ink }]}>No support tickets yet</Text>
              <Text style={[styles.emptySubtext, { color: c.muted }]}>
                Run into a problem? File a ticket and we&apos;ll take a look.
              </Text>
            </View>
          )
        }
        renderItem={({ item: t }) => {
          const status = STATUS_STYLE[t.status];
          return (
            <Pressable
              onPress={() => navigation.navigate('SupportTicketDetail', { ticketId: t.id })}
              style={[styles.row, { backgroundColor: c.card, borderColor: c.border }]}
              accessibilityRole="button"
            >
              <View style={styles.rowMain}>
                <View style={styles.rowTop}>
                  <Text style={[styles.ticketNumber, { color: c.muted }]}>{t.ticketNumber}</Text>
                  <Text style={[styles.categoryTag, { color: c.muted, borderColor: c.border }]}>{CATEGORY_LABELS[t.category]}</Text>
                </View>
                <Text style={[styles.subject, { color: c.ink }]} numberOfLines={1}>{t.subject}</Text>
                <Text style={[styles.meta, { color: c.muted }]}>
                  Opened {fmtDate(t.createdAt)} · Updated {fmtDate(t.updatedAt)}
                </Text>
              </View>
              <Text style={[styles.statusBadge, { backgroundColor: status.bg(c), color: status.fg(c) }]}>{status.label}</Text>
            </Pressable>
          );
        }}
      />

      {showNew ? (
        <NewTicketSheet
          onClose={() => setShowNew(false)}
          onCreated={(ticket) => {
            setShowNew(false);
            void queryClient.invalidateQueries({ queryKey: ['support-tickets-mine'] });
            navigation.navigate('SupportTicketDetail', { ticketId: ticket.id });
          }}
        />
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  content: { padding: spacing.md, paddingBottom: spacing.xl, flexGrow: 1 },
  header: { flexDirection: 'row', alignItems: 'flex-start', justifyContent: 'space-between', marginBottom: spacing.md, gap: spacing.sm },
  headerText: { flex: 1 },
  title: { fontSize: 20, fontWeight: '700' },
  subtitle: { fontSize: 13, marginTop: 2 },
  newButton: { flexDirection: 'row', alignItems: 'center', gap: 6, borderRadius: radius.md, paddingHorizontal: 14, paddingVertical: 10 },
  newButtonText: { fontSize: 13, fontWeight: '600' },
  centered: { alignItems: 'center', justifyContent: 'center', paddingVertical: spacing.xl, gap: spacing.xs },
  emptyText: { fontSize: 14, fontWeight: '600', textAlign: 'center' },
  emptySubtext: { fontSize: 12, textAlign: 'center' },
  retryText: { fontSize: 13, fontWeight: '600' },
  row: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing.sm,
    borderWidth: 1, borderRadius: radius.md, padding: spacing.md, marginBottom: spacing.xs,
  },
  rowMain: { flex: 1 },
  rowTop: { flexDirection: 'row', alignItems: 'center', gap: spacing.xs },
  ticketNumber: { fontSize: 11, fontFamily: 'monospace' },
  categoryTag: { fontSize: 10, borderWidth: 1, borderRadius: radius.md, paddingHorizontal: 6, paddingVertical: 2 },
  subject: { fontSize: 14, fontWeight: '600', marginTop: 3 },
  meta: { fontSize: 11, marginTop: 3 },
  statusBadge: { fontSize: 10, fontWeight: '700', textTransform: 'uppercase', borderRadius: radius.md, paddingHorizontal: 8, paddingVertical: 4 },
});
