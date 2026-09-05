import { ActivityIndicator, Modal, StyleSheet, View } from 'react-native';
import { useQuery } from '@tanstack/react-query';
import { transactionsApi } from '../api/endpoints';
import { Button } from './Button';
import { Card, DetailField, EmptyState, SectionHeading } from './Card';
import { toUserMessage } from '../lib/apiError';
import { fmtDate } from '../lib/format';
import { spacing, useTheme } from '../theme';

const SOURCE_LABELS: Record<string, string> = {
  MANUAL: "You entered this transaction yourself.",
  GMAIL_IMPORT: 'Imported from a Gmail receipt, not a bank statement row.',
  CSV_IMPORT: 'Imported before Finora tracked exactly which row a transaction came from.',
};

// The statement import itself was deleted (a superseded re-upload, or account-purge cleanup) --
// a genuinely different reason than "never had a tracked row" above, so it gets its own message
// rather than reusing CSV_IMPORT's, which would tell a user their row predates tracking when it
// was actually tracked and the file record is just gone.
const STATEMENT_DELETED_MESSAGE = "The bank statement this was imported from is no longer available.";

/**
 * "Where did this number come from?" (Track C/C7) -- the user-scoped counterpart to the
 * admin-only Import Row Trace. Modeled on StatementHistoryScreen's StatementDetailModal: same
 * plain Modal + Card + backdrop shape, same lazy `enabled`-gated useQuery keyed by id, no shared
 * bottom-sheet primitive exists in this codebase to reuse instead.
 */
export function TransactionSourceModal({ transactionId, onClose }: { transactionId: string | null; onClose: () => void }) {
  const c = useTheme();
  const { data, isLoading, isError, error } = useQuery({
    queryKey: ['transaction-source', transactionId],
    queryFn: () => transactionsApi.source(transactionId as string),
    enabled: transactionId !== null,
  });

  if (transactionId === null) return null;

  return (
    <Modal visible transparent animationType="slide" onRequestClose={onClose}>
      <View style={styles.backdrop}>
        <Card style={styles.card}>
          <SectionHeading title="Where this came from" />
          {isLoading ? (
            <ActivityIndicator color={c.primary} style={styles.loading} />
          ) : isError ? (
            <EmptyState message={toUserMessage(error, "Couldn't load where this came from.")} />
          ) : !data?.available ? (
            <EmptyState message={
              data?.statementDeleted
                ? STATEMENT_DELETED_MESSAGE
                : SOURCE_LABELS[data?.sourceLabel ?? 'MANUAL'] ?? 'No source information is available for this transaction.'
            } />
          ) : (
            <View style={styles.fieldGrid}>
              <DetailField label="Bank statement" value={data.fileName ?? 'Unknown file'} />
              {data.accountName ? <DetailField label="Account" value={data.accountName} /> : null}
              <DetailField label="Row in statement" value={`Row ${data.rowPosition}`} />
              {data.statementPeriodStart || data.statementPeriodEnd ? (
                <DetailField
                  label="Statement period"
                  // Each date guarded independently -- statementPeriodStart/End are two
                  // separately nullable columns (a real, documented extraction gap), not a
                  // both-or-neither pair. Joining fmtDate(end) unguarded here used to render the
                  // literal text "null" whenever only the start date had been extracted.
                  value={[fmtDate(data.statementPeriodStart), fmtDate(data.statementPeriodEnd)].filter(Boolean).join(' – ')}
                />
              ) : null}
              <DetailField label="Imported" value={fmtDate(data.importedAt) ?? 'Unknown'} />
            </View>
          )}
          <Button label="Close" variant="link" onPress={onClose} />
        </Card>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  backdrop: {
    flex: 1, backgroundColor: 'rgba(0,0,0,0.45)',
    alignItems: 'center', justifyContent: 'center', padding: spacing.md,
  },
  card: { width: '100%', maxWidth: 420, gap: spacing.sm },
  loading: { marginVertical: spacing.md },
  fieldGrid: { flexDirection: 'row', flexWrap: 'wrap' },
});
