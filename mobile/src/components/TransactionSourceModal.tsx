import { ActivityIndicator, Modal, StyleSheet, Text, View } from 'react-native';
import { useQuery } from '@tanstack/react-query';
import { transactionsApi } from '../api/endpoints';
import { Button } from './Button';
import { Card, EmptyState, SectionHeading } from './Card';
import { toUserMessage } from '../lib/apiError';
import { fmtDate } from '../lib/format';
import { spacing, useTheme } from '../theme';

const SOURCE_LABELS: Record<string, string> = {
  MANUAL: "You entered this transaction yourself.",
  GMAIL_IMPORT: 'Imported from a Gmail receipt, not a bank statement row.',
  CSV_IMPORT: 'Imported before Finora tracked exactly which row a transaction came from.',
};

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
            <EmptyState message={SOURCE_LABELS[data?.sourceLabel ?? 'MANUAL'] ?? 'No source information is available for this transaction.'} />
          ) : (
            <View style={styles.fieldGrid}>
              <Field label="Bank statement" value={data.fileName ?? 'Unknown file'} />
              {data.accountName ? <Field label="Account" value={data.accountName} /> : null}
              <Field label="Row in statement" value={`Row ${data.rowPosition}`} />
              {data.statementPeriodStart ? (
                <Field
                  label="Statement period"
                  value={`${fmtDate(data.statementPeriodStart)} – ${fmtDate(data.statementPeriodEnd)}`}
                />
              ) : null}
              <Field label="Imported" value={fmtDate(data.importedAt) ?? 'Unknown'} />
            </View>
          )}
          <Button label="Close" variant="link" onPress={onClose} />
        </Card>
      </View>
    </Modal>
  );
}

function Field({ label, value }: { label: string; value: string }) {
  const c = useTheme();
  return (
    <View style={styles.field}>
      <Text style={[styles.fieldLabel, { color: c.muted }]}>{label}</Text>
      <Text style={[styles.fieldValue, { color: c.ink }]}>{value}</Text>
    </View>
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
  field: { width: '50%', paddingVertical: 6, paddingRight: spacing.sm },
  fieldLabel: { fontSize: 12, fontWeight: '500', marginBottom: 4 },
  fieldValue: { fontSize: 13, lineHeight: 18 },
});
