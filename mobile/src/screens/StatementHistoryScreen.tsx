import { useState } from 'react';
import {
  ActivityIndicator, Alert, FlatList, Modal, Pressable, ScrollView, StyleSheet, Text, TextInput, View,
} from 'react-native';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigation } from '@react-navigation/native';
import type { BottomTabNavigationProp } from '@react-navigation/bottom-tabs';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { usePreventScreenCapture } from 'expo-screen-capture';
import Ionicons from '@expo/vector-icons/Ionicons';
import { statementImportsApi } from '../api/endpoints';
import { PDF_PASSWORD_INVALID, PDF_PASSWORD_REQUIRED } from '../api/errorCodes';
import { Button } from '../components/Button';
import { Card, EmptyState, SectionHeading } from '../components/Card';
import { apiErrorCode, toUserMessage } from '../lib/apiError';
import { fmtCurrency, fmtDate } from '../lib/format';
import { invalidateFinancialData } from '../lib/invalidateFinancialData';
import { useKeyedSingleFlight } from '../lib/useSingleFlight';
import { useLargeFontScale } from '../lib/useLargeFontScale';
import { radius, spacing, useTheme } from '../theme';
import type { AppTabParamList } from '../navigation/types';
import type { AccountStatementGroup, StatementSummary } from '../types';

/** Mirrors the backend's 7-day retention window for a deleted account's history. */
function daysUntilRemoved(deletedAt: string): string {
  const removedAtMs = new Date(deletedAt).getTime() + 7 * 24 * 60 * 60 * 1000;
  const daysLeft = Math.max(0, Math.ceil((removedAtMs - Date.now()) / (24 * 60 * 60 * 1000)));
  if (daysLeft === 0) return 'removing today';
  return `removing in ${daysLeft} day${daysLeft === 1 ? '' : 's'}`;
}

type Detail = { mode: 'summary' | 'transactions'; statement: StatementSummary };

/**
 * Statement History — every imported statement, grouped by the account it landed in rather than by
 * the file it came from.
 *
 * Lives in the More stack next to Accounts. The one behaviour worth knowing before reading: a
 * statement originally uploaded as a password-protected PDF is still stored encrypted (the
 * upload-time password is deliberately never persisted), so re-importing one has to ask for the
 * password again. This tries WITHOUT one first and prompts only when the server says it is needed
 * — see handleReimport.
 */
export function StatementHistoryScreen() {
  // SEC-17 (docs/quality/bug-reports/2026-08-19-security-review-findings.md). Account numbers,
  // balances and per-statement transaction detail render here -- see DashboardScreen's identical
  // comment for the platform coverage/degrade behavior.
  usePreventScreenCapture();
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const queryClient = useQueryClient();
  const navigation = useNavigation();
  const reimportGuard = useKeyedSingleFlight();

  const [openAccounts, setOpenAccounts] = useState<Set<string>>(new Set());
  const [busyId, setBusyId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [detail, setDetail] = useState<Detail | null>(null);
  // Set only once the server has told us this statement needs a password. `wrong` separates "we
  // have not asked yet" from "you answered and the document rejected it".
  const [passwordPrompt, setPasswordPrompt] = useState<{ statement: StatementSummary; wrong: boolean } | null>(null);

  const { data: groups = [], isLoading, isError } = useQuery({
    queryKey: ['statement-imports'],
    queryFn: () => statementImportsApi.listGroupedByAccount(),
  });

  function toggleAccount(accountId: string) {
    setOpenAccounts((prev) => {
      const next = new Set(prev);
      if (next.has(accountId)) next.delete(accountId);
      else next.add(accountId);
      return next;
    });
  }

  /**
   * Re-import replays the ORIGINAL stored bytes, which for a protected PDF are still encrypted.
   *
   * Tries without a password first, unlike the upload flow, which offers the field up front. The
   * difference is what a failed attempt costs: on upload it means sending the whole file over the
   * network for nothing, so asking first is cheaper; here the bytes are already on the server, so
   * "just try it" is one small request. Every statement that never needed a password — most of
   * them — keeps its single-tap re-import.
   */
  async function handleReimport(statement: StatementSummary, password?: string) {
    // Keyed on the statement, not global: the `busyId` state below disables only THIS row, so a
    // guard that blocked the whole screen would falsely drop a tap on a different statement. What
    // it protects against is two rapid taps on the SAME row landing before the first setBusyId
    // reaches a render -- each would otherwise stage its own server-side session for one re-import
    // (see B5 in the mobile-correctness-trust-roadmap: the confirm side is already claimed
    // atomically by V133, but nothing stopped a duplicate staging session from being created here).
    await reimportGuard(statement.id, async () => {
      setBusyId(statement.id);
      setError(null);
      try {
        const result = await statementImportsApi.reimport(statement.id, password);
        setPasswordPrompt(null);
        // Hand the staged rows to the Import TAB rather than rebuilding the review UI here -- it is
        // the same review and confirm the user already knows. Import lives in the tab navigator and
        // this screen lives in the More stack, so the jump goes through the parent.
        navigation.getParent<BottomTabNavigationProp<AppTabParamList>>()?.navigate('Import', {
          reimport: {
            statementImportId: statement.id,
            accountId: result.accountId,
            accountName: result.accountName,
            staging: result.staging,
            password,
            nonce: Date.now(),
          },
        });
      } catch (e) {
        const code = apiErrorCode(e);
        if (code === PDF_PASSWORD_REQUIRED || code === PDF_PASSWORD_INVALID) {
          // Not a re-import failure and not shown as one -- the statement is intact, it just has not
          // been unlocked. Keeping the prompt open on INVALID preserves what was typed.
          setPasswordPrompt({ statement, wrong: code === PDF_PASSWORD_INVALID });
        } else {
          setError(toUserMessage(e, 'Could not re-import this statement.'));
        }
      } finally {
        setBusyId(null);
      }
    });
  }

  function confirmDelete(statement: StatementSummary) {
    Alert.alert(
      'Delete this import?',
      `This removes only the ${statement.transactionsImported} transaction(s) "${statement.fileName}" imported — nothing else.`,
      [
        { text: 'Cancel', style: 'cancel' },
        { text: 'Delete', style: 'destructive', onPress: () => void handleDelete(statement) },
      ]
    );
  }

  async function handleDelete(statement: StatementSummary) {
    setBusyId(statement.id);
    setError(null);
    try {
      await statementImportsApi.remove(statement.id);
      await queryClient.invalidateQueries({ queryKey: ['statement-imports'] });
      invalidateFinancialData(queryClient);
    } catch (e) {
      setError(toUserMessage(e, 'Could not delete this statement import.'));
    } finally {
      setBusyId(null);
    }
  }

  async function handleShare(statement: StatementSummary) {
    setBusyId(statement.id);
    setError(null);
    try {
      await statementImportsApi.downloadFile(statement.id, statement.fileName);
    } catch (e) {
      setError(toUserMessage(e, 'Could not open the original file.'));
    } finally {
      setBusyId(null);
    }
  }

  return (
    <View style={[styles.flex, { backgroundColor: c.bg, paddingTop: insets.top + spacing.md }]}>
      <ScrollView contentContainerStyle={styles.padded}>
        <Text style={[styles.title, { color: c.ink }]}>Statement History</Text>
        <Text style={[styles.body, { color: c.muted, marginBottom: spacing.md }]}>
          Every imported statement, organized by account — not by which file you uploaded.
        </Text>

        {error ? (
          <Card style={{ ...styles.section, borderColor: c.danger }}>
            <Text style={[styles.body, { color: c.danger }]}>{error}</Text>
          </Card>
        ) : null}

        {isLoading ? (
          <ActivityIndicator color={c.primary} />
        ) : isError ? (
          <Card style={styles.section}>
            <EmptyState message="Couldn't load your statement history. Pull back and try again." />
          </Card>
        ) : groups.length === 0 ? (
          <Card style={styles.section}>
            <EmptyState message="No statements imported yet. Use the Import tab to add one." />
          </Card>
        ) : (
          groups.map((group) => (
            <AccountGroupCard
              key={group.accountId}
              group={group}
              // A lone account opens on its own -- there is nothing to choose between.
              open={openAccounts.has(group.accountId) || groups.length === 1}
              onToggle={() => toggleAccount(group.accountId)}
              busyId={busyId}
              onReimport={(s) => void handleReimport(s)}
              onDelete={confirmDelete}
              onShare={(s) => void handleShare(s)}
              onView={(mode, statement) => setDetail({ mode, statement })}
            />
          ))
        )}
      </ScrollView>

      {detail ? <StatementDetailModal detail={detail} onClose={() => setDetail(null)} /> : null}

      {passwordPrompt ? (
        <ReimportPasswordModal
          prompt={passwordPrompt}
          busy={busyId === passwordPrompt.statement.id}
          onSubmit={(password) => void handleReimport(passwordPrompt.statement, password)}
          onClose={() => setPasswordPrompt(null)}
        />
      ) : null}
    </View>
  );
}

function AccountGroupCard({
  group, open, onToggle, busyId, onReimport, onDelete, onShare, onView,
}: {
  group: AccountStatementGroup;
  open: boolean;
  onToggle: () => void;
  busyId: string | null;
  onReimport: (s: StatementSummary) => void;
  onDelete: (s: StatementSummary) => void;
  onShare: (s: StatementSummary) => void;
  onView: (mode: 'summary' | 'transactions', s: StatementSummary) => void;
}) {
  const c = useTheme();

  return (
    <Card style={styles.section}>
      <Pressable
        onPress={onToggle}
        accessibilityRole="button"
        accessibilityState={{ expanded: open }}
        accessibilityLabel={`${group.accountName}, ${group.statements.length} statements`}
        style={styles.groupHeader}
      >
        <View style={styles.flexShrink}>
          <Text style={[styles.groupName, { color: c.ink }]} numberOfLines={1}>{group.accountName}</Text>
          <Text style={[styles.body, { color: c.mutedInk }]}>
            {group.bank?.shortName ?? 'Other'} · {group.statements.length} statement
            {group.statements.length === 1 ? '' : 's'}
          </Text>
          {group.deleted && group.deletedAt ? (
            <Text style={[styles.body, { color: c.warningInk }]}>
              Account deleted — {daysUntilRemoved(group.deletedAt)}
            </Text>
          ) : null}
        </View>
        <Ionicons name={open ? 'chevron-down' : 'chevron-forward'} size={18} color={c.muted} />
      </Pressable>

      {open
        ? group.statements.map((s) => {
            const busy = busyId === s.id;
            return (
              <View key={s.id} style={[styles.statementRow, { borderTopColor: c.border }]}>
                <Text style={[styles.fileName, { color: c.ink }]} numberOfLines={2}>{s.fileName}</Text>
                <Text style={[styles.body, { color: c.mutedInk }]}>
                  {fmtDate(s.statementPeriodStart) ?? '—'} – {fmtDate(s.statementPeriodEnd) ?? '—'} ·{' '}
                  {s.transactionsImported} txns
                  {s.duplicateCount > 0 ? ` · ${s.duplicateCount} duplicate${s.duplicateCount === 1 ? '' : 's'}` : ''}
                </Text>

                <View style={styles.actionRow}>
                  <RowAction label="Summary" icon="information-circle-outline" onPress={() => onView('summary', s)} busy={busy} />
                  <RowAction label="Transactions" icon="list-outline" onPress={() => onView('transactions', s)} busy={busy} />
                  <RowAction
                    label="Re-import"
                    icon="refresh-outline"
                    onPress={() => onReimport(s)}
                    busy={busy}
                    // The account is gone, so there is nowhere to replay these rows into.
                    disabled={group.deleted}
                  />
                  <RowAction label="Share file" icon="share-outline" onPress={() => onShare(s)} busy={busy} />
                  <RowAction label="Delete" icon="trash-outline" onPress={() => onDelete(s)} busy={busy} danger />
                </View>
              </View>
            );
          })
        : null}
    </Card>
  );
}

function RowAction({
  label, icon, onPress, busy, danger, disabled,
}: {
  label: string;
  icon: keyof typeof Ionicons.glyphMap;
  onPress: () => void;
  busy?: boolean;
  danger?: boolean;
  disabled?: boolean;
}) {
  const c = useTheme();
  const isDisabled = busy || disabled;
  return (
    <Pressable
      onPress={onPress}
      disabled={isDisabled}
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{ disabled: !!isDisabled, busy: !!busy }}
      hitSlop={6}
      style={[styles.action, { borderColor: c.border }, isDisabled && styles.actionDisabled]}
    >
      <Ionicons name={icon} size={14} color={danger ? c.danger : c.muted} />
      <Text style={[styles.actionText, { color: danger ? c.danger : c.muted }]}>{label}</Text>
    </Pressable>
  );
}

/**
 * Asks for the document password of a protected PDF being re-imported.
 *
 * Mounted only after the server has said one is needed, so unlike the upload flow's field there is
 * no "leave blank" case to explain — reaching this already means blank was tried and rejected.
 * Submitting is therefore gated on a non-empty value.
 */
function ReimportPasswordModal({
  prompt, busy, onSubmit, onClose,
}: {
  prompt: { statement: StatementSummary; wrong: boolean };
  busy: boolean;
  onSubmit: (password: string) => void;
  onClose: () => void;
}) {
  const c = useTheme();
  const [password, setPassword] = useState('');

  return (
    <Modal visible transparent animationType="fade" onRequestClose={onClose}>
      <View style={styles.modalBackdrop}>
        <Card style={styles.modalCard}>
          <SectionHeading title="Unlock this statement" />
          <Text style={[styles.body, { color: c.muted }]}>
            <Text style={{ color: c.ink }}>{prompt.statement.fileName}</Text> is password protected.
            Fynora doesn&apos;t store statement passwords, so re-importing needs it again.
          </Text>

          <Text style={[styles.fieldLabel, { color: c.ink }]}>Statement password</Text>
          <TextInput
            value={password}
            onChangeText={setPassword}
            secureTextEntry
            autoCapitalize="none"
            autoCorrect={false}
            autoFocus
            // The bank's password for one document, not a Fynora credential -- it does not belong
            // in the OS keychain alongside real logins, and it changes every statement.
            autoComplete="off"
            textContentType="none"
            accessibilityLabel="Statement password"
            editable={!busy}
            style={[styles.input, { color: c.ink, borderColor: c.border, backgroundColor: c.inputBg }]}
          />
          <Text style={[styles.helpText, { color: prompt.wrong ? c.danger : c.mutedInk }]}>
            {prompt.wrong
              ? "That password didn't open this statement — check it and try again."
              : 'The password your bank uses for this statement.'}
          </Text>

          <Button
            label={busy ? 'Unlocking…' : 'Re-import statement'}
            onPress={() => onSubmit(password)}
            disabled={!password}
            loading={busy}
          />
          <Button label="Cancel" variant="link" onPress={onClose} />
        </Card>
      </View>
    </Modal>
  );
}

function StatementDetailModal({ detail, onClose }: { detail: Detail; onClose: () => void }) {
  const c = useTheme();
  const largeText = useLargeFontScale();
  const { statement, mode } = detail;

  const { data: transactions, isLoading } = useQuery({
    queryKey: ['statement-import-transactions', statement.id],
    queryFn: () => statementImportsApi.transactions(statement.id),
    enabled: mode === 'transactions',
  });

  return (
    <Modal visible transparent animationType="slide" onRequestClose={onClose}>
      <View style={styles.modalBackdrop}>
        <Card style={styles.detailCard}>
          <SectionHeading title={mode === 'summary' ? 'Import Summary' : 'Imported Transactions'} />
          <Text style={[styles.body, { color: c.muted }]} numberOfLines={2}>{statement.fileName}</Text>

          {mode === 'summary' ? (
            <View style={styles.fieldGrid}>
              <Field label="Statement period" value={
                statement.statementPeriodStart
                  ? `${fmtDate(statement.statementPeriodStart)} – ${fmtDate(statement.statementPeriodEnd)}`
                  : 'Unknown'
              } />
              <Field label="Imported" value={fmtDate(statement.importedAt) ?? '—'} />
              <Field label="Opening balance" value={statement.openingBalance != null ? fmtCurrency(statement.openingBalance) : '—'} />
              <Field label="Closing balance" value={statement.closingBalance != null ? fmtCurrency(statement.closingBalance) : '—'} />
              <Field label="Transactions imported" value={String(statement.transactionsImported)} />
              <Field label="Transactions skipped" value={String(statement.transactionsSkipped)} />
              <Field label="Duplicates flagged" value={String(statement.duplicateCount)} />
            </View>
          ) : isLoading ? (
            <ActivityIndicator color={c.primary} style={styles.detailLoading} />
          ) : !transactions || transactions.length === 0 ? (
            <EmptyState message="This import's transactions are no longer on the books." />
          ) : (
            <FlatList
              data={transactions}
              keyExtractor={(t) => t.id}
              style={styles.detailList}
              renderItem={({ item }) => (
                <View style={[styles.txnRow, { borderBottomColor: c.border }]}>
                  <View style={styles.flexShrink}>
                    <Text style={[styles.body, { color: c.ink }]} numberOfLines={largeText ? 2 : 1}>{item.description}</Text>
                    <Text style={[styles.body, { color: c.mutedInk }]}>{fmtDate(item.date)}</Text>
                  </View>
                  <Text style={[styles.body, { color: item.type === 'INCOME' ? c.primary : c.ink }]}>
                    {fmtCurrency(item.amount)}
                  </Text>
                </View>
              )}
            />
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
      <Text style={[styles.body, { color: c.ink }]}>{value}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  flexShrink: { flexShrink: 1 },
  padded: { paddingHorizontal: spacing.md, paddingBottom: spacing.xl },
  title: { fontSize: 22, fontWeight: '700' },
  body: { fontSize: 13, lineHeight: 18 },
  section: { marginBottom: spacing.md },
  groupHeader: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing.sm },
  groupName: { fontSize: 15, fontWeight: '600' },
  statementRow: { borderTopWidth: 1, paddingTop: spacing.sm, marginTop: spacing.sm, gap: 4 },
  fileName: { fontSize: 14, fontWeight: '500' },
  actionRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 6, marginTop: 6 },
  action: {
    flexDirection: 'row', alignItems: 'center', gap: 4,
    borderWidth: 1, borderRadius: radius.md, paddingHorizontal: 8, minHeight: 32,
  },
  actionDisabled: { opacity: 0.4 },
  actionText: { fontSize: 12 },
  modalBackdrop: {
    flex: 1, backgroundColor: 'rgba(0,0,0,0.45)',
    alignItems: 'center', justifyContent: 'center', padding: spacing.md,
  },
  modalCard: { width: '100%', maxWidth: 420, gap: spacing.sm },
  detailCard: { width: '100%', maxWidth: 420, maxHeight: '80%', gap: spacing.sm },
  detailList: { maxHeight: 360 },
  detailLoading: { marginVertical: spacing.md },
  fieldGrid: { flexDirection: 'row', flexWrap: 'wrap' },
  field: { width: '50%', paddingVertical: 6, paddingRight: spacing.sm },
  fieldLabel: { fontSize: 12, fontWeight: '500', marginBottom: 4 },
  helpText: { fontSize: 12, lineHeight: 17 },
  input: { borderWidth: 1, borderRadius: radius.md, paddingHorizontal: 12, minHeight: 48, fontSize: 15 },
  txnRow: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    gap: spacing.sm, paddingVertical: 8, borderBottomWidth: 1,
  },
});
