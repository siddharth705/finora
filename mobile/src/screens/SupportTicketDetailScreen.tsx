import { ActivityIndicator, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import { useQuery } from '@tanstack/react-query';
import Ionicons from '@expo/vector-icons/Ionicons';
import { supportApi, type SupportTicketCategory, type SupportTicketStatus } from '../api/endpoints';
import { fmtDate } from '../lib/format';
import { toUserMessage } from '../lib/apiError';
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

const STATUS_STYLE: Record<SupportTicketStatus, { label: string; bg: (c: ReturnType<typeof useTheme>) => string; fg: (c: ReturnType<typeof useTheme>) => string }> = {
  OPEN: { label: 'Open', bg: (c) => c.primaryLight, fg: (c) => c.primary },
  IN_PROGRESS: { label: 'In Progress', bg: (c) => c.warningBg, fg: (c) => c.warningInk },
  RESOLVED: { label: 'Resolved', bg: (c) => c.successBg, fg: (c) => c.success },
  CLOSED: { label: 'Closed', bg: (c) => c.bg, fg: (c) => c.muted },
};

function bytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / (1024 * 1024)).toFixed(1)} MB`;
}

type Props = NativeStackScreenProps<MoreStackParamList, 'SupportTicketDetail'>;

/** Support, Help & Feedback v1, Phase 8 (mobile). Ported from frontend's SupportTicketDetail.tsx
 *  -- see that file's own doc comment for why this is the user-facing render only: no
 *  claim/status-change controls, no internal-notes panel. Same shape, mobile idioms. */
export function SupportTicketDetailScreen({ route }: Props) {
  const c = useTheme();
  const { ticketId } = route.params;

  const ticketQuery = useQuery({
    queryKey: ['support-ticket-detail', ticketId],
    queryFn: () => supportApi.detail(ticketId),
    // A 404 means "not yours, or doesn't exist" -- same reasoning as the web page's identical
    // query, and StatementHistoryScreen's own owned-resource fetches.
    retry: false,
  });

  if (ticketQuery.isLoading) {
    return (
      <View style={[styles.centered, { backgroundColor: c.bg }]}>
        <ActivityIndicator size="large" color={c.primary} />
      </View>
    );
  }

  if (!ticketQuery.data) {
    return (
      <View style={[styles.centered, { backgroundColor: c.bg }]}>
        <Ionicons name="help-circle-outline" size={28} color={c.muted} />
        <Text style={[styles.notFoundTitle, { color: c.ink }]}>Ticket not found</Text>
        <Text style={[styles.notFoundBody, { color: c.muted }]}>
          {toUserMessage(ticketQuery.error, "This ticket doesn't exist, or isn't yours to view.")}
        </Text>
      </View>
    );
  }

  const t = ticketQuery.data;
  const status = STATUS_STYLE[t.status];

  return (
    <ScrollView style={{ backgroundColor: c.bg }} contentContainerStyle={styles.content}>
      <View style={[styles.card, { backgroundColor: c.card, borderColor: c.border }]}>
        <View style={styles.headerRow}>
          <View style={styles.headerText}>
            <Text style={[styles.meta, { color: c.muted }]}>{t.ticketNumber} · {CATEGORY_LABELS[t.category]}</Text>
            <Text style={[styles.subject, { color: c.ink }]}>{t.subject}</Text>
            <Text style={[styles.meta, { color: c.muted }]}>Opened {fmtDate(t.createdAt)}</Text>
          </View>
          <Text style={[styles.statusBadge, { backgroundColor: status.bg(c), color: status.fg(c) }]}>{status.label}</Text>
        </View>

        <Text style={[styles.description, { color: c.ink }]}>{t.description}</Text>

        {t.attachments.length > 0 ? (
          <View style={styles.attachments}>
            {t.attachments.map((a) => (
              <Pressable
                key={a.id}
                onPress={() => void supportApi.downloadAttachment(t.id, a.id, a.filename, a.contentType)}
                style={styles.attachmentRow}
                accessibilityRole="button"
                accessibilityLabel={`${a.filename}, ${bytes(a.sizeBytes)}. Share`}
              >
                <Ionicons name="attach" size={14} color={c.primary} />
                <Text style={[styles.attachmentName, { color: c.primary }]} numberOfLines={1}>{a.filename}</Text>
                <Text style={[styles.attachmentSize, { color: c.muted }]}>({bytes(a.sizeBytes)})</Text>
                <Ionicons name="share-outline" size={14} color={c.muted} />
              </Pressable>
            ))}
          </View>
        ) : null}

        {t.status === 'RESOLVED' || t.status === 'CLOSED' ? (
          <Text style={[styles.footerNote, { color: c.muted, borderTopColor: c.border }]}>
            This ticket is {t.status === 'RESOLVED' ? 'resolved' : 'closed'} and can&apos;t be
            reopened — file a new ticket if the issue comes back.
          </Text>
        ) : null}
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  centered: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: spacing.lg, gap: spacing.xs },
  notFoundTitle: { fontSize: 14, fontWeight: '600' },
  notFoundBody: { fontSize: 12, textAlign: 'center' },
  content: { padding: spacing.md },
  card: { borderWidth: 1, borderRadius: radius.lg, padding: spacing.md },
  headerRow: { flexDirection: 'row', alignItems: 'flex-start', justifyContent: 'space-between', gap: spacing.sm },
  headerText: { flex: 1 },
  meta: { fontSize: 11 },
  subject: { fontSize: 17, fontWeight: '700', marginTop: 4, marginBottom: 4 },
  statusBadge: { fontSize: 10, fontWeight: '700', textTransform: 'uppercase', borderRadius: radius.md, paddingHorizontal: 8, paddingVertical: 4 },
  description: { fontSize: 14, lineHeight: 20, marginTop: spacing.md },
  attachments: { marginTop: spacing.md, gap: spacing.xs },
  attachmentRow: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  attachmentName: { fontSize: 13, flexShrink: 1 },
  attachmentSize: { fontSize: 12 },
  footerNote: { fontSize: 12, marginTop: spacing.md, paddingTop: spacing.md, borderTopWidth: StyleSheet.hairlineWidth },
});
