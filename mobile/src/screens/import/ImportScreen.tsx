import { useMemo, useState } from 'react';
import {
  FlatList, Pressable, StyleSheet, Text, TextInput, View,
} from 'react-native';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Button } from '../../components/Button';
import { Card, SectionHeading } from '../../components/Card';
import { CategoryPickerModal } from './CategoryPickerModal';
import { StagedRowCard } from './StagedRowCard';
import { accountsApi, categoriesApi, importApi } from '../../api/endpoints';
import { toUserMessage } from '../../lib/apiError';
import { fmtCurrency } from '../../lib/format';
import { invalidateFinancialData } from '../../lib/invalidateFinancialData';
import {
  buildNewAccountPayload, buildRowPayload, initialAccountForm, initialCategories, initialInclusion,
  type NewAccountForm,
} from '../../lib/importPayload';
import { pickStatement, type StatementFormat } from '../../lib/statementFile';
import { radius, spacing, useTheme } from '../../theme';
import type { DetectedAccountInfo, ImportSummary, StagedRow, UnparseableRow } from '../../types';

type Step = 'upload' | 'review' | 'summary';
type AccountChoice = 'existing' | 'new';

export function ImportScreen() {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const queryClient = useQueryClient();

  const [step, setStep] = useState<Step>('upload');
  const [error, setError] = useState<string | null>(null);
  const [uploadProgress, setUploadProgress] = useState<number | null>(null);
  const [confirming, setConfirming] = useState(false);

  const [sessionId, setSessionId] = useState<string | null>(null);
  const [fileFormat, setFileFormat] = useState<StatementFormat | null>(null);
  const [rows, setRows] = useState<StagedRow[]>([]);
  const [included, setIncluded] = useState<boolean[]>([]);
  const [chosenCategory, setChosenCategory] = useState<string[]>([]);
  const [unparseableRows, setUnparseableRows] = useState<UnparseableRow[]>([]);
  const [detected, setDetected] = useState<DetectedAccountInfo | null>(null);

  const [accountChoice, setAccountChoice] = useState<AccountChoice>('new');
  const [selectedAccountId, setSelectedAccountId] = useState('');
  const [accountForm, setAccountForm] = useState<NewAccountForm>(initialAccountForm(null));

  const [summary, setSummary] = useState<ImportSummary | null>(null);
  const [categoryPickerFor, setCategoryPickerFor] = useState<number | null>(null);

  // Non-critical: the screen still works with empty lists, just with fewer prefilled defaults.
  const { data: categories = [] } = useQuery({
    queryKey: ['categories'],
    queryFn: () => categoriesApi.list().then((cs) => cs.map((x) => x.name)),
  });
  const { data: existingAccounts = [] } = useQuery({
    queryKey: ['accounts'],
    queryFn: () => accountsApi.list(),
  });

  const includedCount = useMemo(() => included.filter(Boolean).length, [included]);

  function resetToUpload() {
    setStep('upload');
    setError(null);
    setUploadProgress(null);
    setSessionId(null);
    setFileFormat(null);
    setRows([]);
    setIncluded([]);
    setChosenCategory([]);
    setUnparseableRows([]);
    setDetected(null);
    setSummary(null);
    setAccountForm(initialAccountForm(null));
  }

  async function handlePick() {
    setError(null);
    let picked;
    try {
      picked = await pickStatement();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not read that file.');
      return;
    }
    // A dismissed picker is not an error and must not show one.
    if (!picked) return;

    setUploadProgress(0);
    setFileFormat(picked.format);
    try {
      const res =
        picked.format === 'PDF'
          ? await importApi.stagePdf(picked.file, setUploadProgress)
          : await importApi.stageCsv(picked.file, setUploadProgress);

      setSessionId(res.sessionId);

      // A single PDF can describe more than one account (a composite statement bundling savings
      // and a credit card). Reviewing those means assigning each section to a different account,
      // and getting it wrong files someone's transactions against the wrong balance -- so this
      // says so plainly rather than guessing or silently importing only the first section.
      if ('multiAccount' in res && res.multiAccount) {
        await importApi.discardSession(res.sessionId).catch(() => {});
        setError(
          'This statement covers more than one account. Multi-account statements can only be imported from the Finora web app for now.'
        );
        setUploadProgress(null);
        return;
      }

      const staging = (res as { staging: NonNullable<typeof res.staging> }).staging;
      setRows(staging.rows);
      setIncluded(initialInclusion(staging.rows));
      setChosenCategory(initialCategories(staging.rows));
      setUnparseableRows(staging.unparseableRows);
      setDetected(staging.detectedAccount);
      setAccountForm(initialAccountForm(staging.detectedAccount));

      // Default to filing into an account that already exists when there is one -- creating a
      // duplicate account is the more annoying mistake to undo.
      if (existingAccounts.length > 0) {
        setAccountChoice('existing');
        setSelectedAccountId(existingAccounts[0].id);
      } else {
        setAccountChoice('new');
      }

      setStep('review');
    } catch (e) {
      setError(toUserMessage(e, 'Could not read that statement.'));
    } finally {
      setUploadProgress(null);
    }
  }

  async function confirmImport() {
    if (!sessionId) return;
    setConfirming(true);
    setError(null);
    try {
      const result = await importApi.confirm({
        sessionId,
        rows: buildRowPayload(rows, included, chosenCategory),
        existingAccountId: accountChoice === 'existing' ? selectedAccountId : null,
        newAccount: accountChoice === 'new' ? buildNewAccountPayload(accountForm, detected) : null,
        statementOpeningBalance: detected?.openingBalance ?? null,
        statementClosingBalance: detected?.closingBalance ?? null,
      });
      setSummary(result);
      setStep('summary');
      invalidateFinancialData(queryClient);
    } catch (e) {
      setError(toUserMessage(e, 'Could not complete the import.'));
    } finally {
      setConfirming(false);
    }
  }

  const header = (
    <View>
      <Text style={[styles.title, { color: c.ink }]}>Import</Text>
      {error ? (
        <Card style={{ ...styles.errorCard, borderColor: c.danger }}>
          <Text style={[styles.errorText, { color: c.danger }]}>{error}</Text>
        </Card>
      ) : null}
    </View>
  );

  // ---- upload ----
  if (step === 'upload') {
    const uploading = uploadProgress !== null;
    return (
      <View style={[styles.flex, { backgroundColor: c.bg, paddingTop: insets.top + spacing.md }]}>
        <View style={styles.padded}>
          {header}
          <Card>
            <SectionHeading title="Import a statement" />
            <Text style={[styles.body, { color: c.muted }]}>
              Choose a CSV or PDF statement from your bank. You'll review every transaction before
              anything is added.
            </Text>

            {uploading ? (
              <View style={styles.progressWrap}>
                <View style={[styles.progressTrack, { backgroundColor: c.border }]}>
                  <View
                    style={[styles.progressFill, { width: `${uploadProgress}%`, backgroundColor: c.primary }]}
                  />
                </View>
                {/* 100% means the bytes finished sending, not that the server finished reading
                    them -- see ProgressCallback's own comment in endpoints.ts. */}
                <Text style={[styles.progressText, { color: c.muted }]}>
                  {uploadProgress === 100 ? 'Reading statement…' : `Uploading… ${uploadProgress}%`}
                </Text>
              </View>
            ) : (
              <Button label="Choose a file" onPress={handlePick} />
            )}
          </Card>
        </View>
      </View>
    );
  }

  // ---- summary ----
  if (step === 'summary' && summary) {
    return (
      <View style={[styles.flex, { backgroundColor: c.bg, paddingTop: insets.top + spacing.md }]}>
        <View style={styles.padded}>
          {header}
          <Card>
            <SectionHeading title="Import complete" />
            <View style={styles.statRow}>
              <Stat label="Imported" value={String(summary.imported)} />
              <Stat label="Skipped" value={String(summary.skipped)} />
              <Stat label="Duplicates" value={String(summary.duplicatesDetected)} />
            </View>
            <View style={styles.statRow}>
              <Stat label="Credits" value={fmtCurrency(summary.totalCredits)} />
              <Stat label="Debits" value={fmtCurrency(summary.totalDebits)} />
            </View>
            {summary.warnings?.length ? (
              <View style={styles.warnings}>
                {summary.warnings.map((w) => (
                  <Text key={w} style={[styles.body, { color: c.warningInk }]}>
                    • {w}
                  </Text>
                ))}
              </View>
            ) : null}
            <View style={styles.actions}>
              <Button label="Import another" onPress={resetToUpload} />
            </View>
          </Card>
        </View>
      </View>
    );
  }

  // ---- review ----
  return (
    <View style={[styles.flex, { backgroundColor: c.bg, paddingTop: insets.top }]}>
      <FlatList
        data={rows}
        keyExtractor={(_, i) => String(i)}
        contentContainerStyle={styles.listContent}
        // Rendering every row of a long statement up front is the difference between a list that
        // opens instantly and one that stalls; a card is a fixed shape, so windowing is safe here.
        initialNumToRender={12}
        windowSize={9}
        removeClippedSubviews
        ListHeaderComponent={
          <View>
            {header}
            <Card style={styles.section}>
              <SectionHeading
                title="Detected"
                action={
                  fileFormat ? (
                    <Text style={[styles.formatBadge, { color: c.primary, backgroundColor: c.primaryLight }]}>
                      {fileFormat}
                    </Text>
                  ) : undefined
                }
              />
              <Text style={[styles.detectedName, { color: c.ink }]}>
                {detected?.suggestedName ?? 'Statement'}
              </Text>
              {detected?.accountNumberMasked ? (
                <Text style={[styles.body, { color: c.muted }]}>{detected.accountNumberMasked}</Text>
              ) : null}
              {detected?.statementPeriodStart && detected?.statementPeriodEnd ? (
                <Text style={[styles.body, { color: c.muted }]}>
                  {detected.statementPeriodStart} to {detected.statementPeriodEnd}
                </Text>
              ) : null}
            </Card>

            <Card style={styles.section}>
              <SectionHeading title="File into" />
              <View style={styles.choiceRow}>
                {(['existing', 'new'] as AccountChoice[]).map((choice) => {
                  const disabled = choice === 'existing' && existingAccounts.length === 0;
                  const active = accountChoice === choice;
                  return (
                    <Pressable
                      key={choice}
                      disabled={disabled}
                      onPress={() => setAccountChoice(choice)}
                      accessibilityRole="button"
                      accessibilityState={{ selected: active, disabled }}
                      style={[
                        styles.choiceChip,
                        { borderColor: active ? c.primary : c.border, backgroundColor: active ? c.primaryLight : 'transparent' },
                        disabled && styles.choiceDisabled,
                      ]}
                    >
                      <Text style={[styles.choiceText, { color: active ? c.primary : c.muted }]}>
                        {choice === 'existing' ? 'An existing account' : 'A new account'}
                      </Text>
                    </Pressable>
                  );
                })}
              </View>

              {accountChoice === 'existing' ? (
                existingAccounts.map((a) => {
                  const active = a.id === selectedAccountId;
                  return (
                    <Pressable
                      key={a.id}
                      onPress={() => setSelectedAccountId(a.id)}
                      accessibilityRole="button"
                      accessibilityState={{ selected: active }}
                      style={[styles.accountRow, { borderColor: active ? c.primary : c.border }]}
                    >
                      <Text style={[styles.accountName, { color: c.ink }]} numberOfLines={1}>
                        {a.name}
                      </Text>
                      <Text style={[styles.body, { color: c.muted }]}>{fmtCurrency(a.balance)}</Text>
                    </Pressable>
                  );
                })
              ) : (
                <View>
                  <Text style={[styles.fieldLabel, { color: c.muted }]}>Account name</Text>
                  <TextInput
                    value={accountForm.name}
                    onChangeText={(name) => setAccountForm((f) => ({ ...f, name }))}
                    placeholder="Imported Account"
                    placeholderTextColor={c.muted}
                    style={[styles.input, { color: c.ink, borderColor: c.border, backgroundColor: c.inputBg }]}
                  />
                  <View style={styles.typeRow}>
                    {(['SAVINGS', 'CREDIT_CARD', 'WALLET', 'INVESTMENT'] as const).map((t) => {
                      const active = accountForm.accountType === t;
                      return (
                        <Pressable
                          key={t}
                          onPress={() => setAccountForm((f) => ({ ...f, accountType: t }))}
                          accessibilityRole="button"
                          accessibilityState={{ selected: active }}
                          style={[
                            styles.typeChip,
                            { borderColor: active ? c.primary : c.border, backgroundColor: active ? c.primaryLight : 'transparent' },
                          ]}
                        >
                          <Text style={[styles.typeText, { color: active ? c.primary : c.muted }]}>
                            {t === 'CREDIT_CARD' ? 'Credit Card' : t.charAt(0) + t.slice(1).toLowerCase()}
                          </Text>
                        </Pressable>
                      );
                    })}
                  </View>
                </View>
              )}
            </Card>

            <View style={styles.rowsHeading}>
              <Text style={[styles.rowsTitle, { color: c.ink }]}>
                {rows.length} transaction{rows.length === 1 ? '' : 's'}
              </Text>
              <Text style={[styles.body, { color: c.muted }]}>{includedCount} selected</Text>
            </View>
          </View>
        }
        renderItem={({ item, index }) => (
          <StagedRowCard
            row={item}
            included={included[index]}
            category={chosenCategory[index]}
            onToggleIncluded={() =>
              setIncluded((prev) => prev.map((v, i) => (i === index ? !v : v)))
            }
            onPressCategory={() => setCategoryPickerFor(index)}
          />
        )}
        ListFooterComponent={
          <View>
            {/* "Never lose information": rows the parser couldn't read are shown rather than
                silently dropped, even though they can never be imported. */}
            {unparseableRows.length > 0 ? (
              <Card style={styles.section}>
                <SectionHeading title={`${unparseableRows.length} rows couldn't be read`} />
                <Text style={[styles.body, { color: c.muted }]}>
                  These are shown so nothing disappears without explanation. They won't be imported.
                </Text>
                {unparseableRows.slice(0, 5).map((u, i) => (
                  <Text key={i} style={[styles.unparseable, { color: c.muted }]} numberOfLines={2}>
                    {u.reason}
                  </Text>
                ))}
              </Card>
            ) : null}

            <View style={styles.actions}>
              <Button
                label={`Import ${includedCount} transaction${includedCount === 1 ? '' : 's'}`}
                onPress={confirmImport}
                loading={confirming}
                disabled={includedCount === 0}
              />
              <View style={styles.cancel}>
                <Button label="Cancel" variant="link" onPress={resetToUpload} />
              </View>
            </View>
          </View>
        }
      />

      <CategoryPickerModal
        visible={categoryPickerFor !== null}
        categories={categories}
        selected={categoryPickerFor !== null ? chosenCategory[categoryPickerFor] : null}
        onSelect={(category) => {
          setChosenCategory((prev) =>
            prev.map((v, i) => (i === categoryPickerFor ? category : v))
          );
          setCategoryPickerFor(null);
        }}
        onClose={() => setCategoryPickerFor(null)}
      />
    </View>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  const c = useTheme();
  return (
    <View style={styles.stat} accessible accessibilityLabel={`${label}: ${value}`}>
      <Text style={[styles.statValue, { color: c.ink }]}>{value}</Text>
      <Text style={[styles.statLabel, { color: c.muted }]}>{label}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  padded: { paddingHorizontal: spacing.md },
  listContent: { paddingHorizontal: spacing.md, paddingBottom: spacing.xl },
  title: { fontSize: 22, fontWeight: '700', marginBottom: spacing.md },
  section: { marginBottom: spacing.sm },
  body: { fontSize: 13, lineHeight: 19 },
  errorCard: { marginBottom: spacing.sm },
  errorText: { fontSize: 13, lineHeight: 19 },
  progressWrap: { marginTop: spacing.sm },
  progressTrack: { height: 6, borderRadius: 3, overflow: 'hidden' },
  progressFill: { height: 6, borderRadius: 3 },
  progressText: { fontSize: 12, marginTop: 6, textAlign: 'center' },
  formatBadge: {
    fontSize: 10,
    fontWeight: '700',
    paddingHorizontal: 8,
    paddingVertical: 3,
    borderRadius: 6,
    overflow: 'hidden',
  },
  detectedName: { fontSize: 16, fontWeight: '600', marginBottom: 2 },
  choiceRow: { flexDirection: 'row', gap: spacing.sm, marginBottom: spacing.sm },
  choiceChip: {
    flex: 1,
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: 10,
    minHeight: 44,
    alignItems: 'center',
    justifyContent: 'center',
  },
  choiceDisabled: { opacity: 0.4 },
  choiceText: { fontSize: 12, fontWeight: '600', textAlign: 'center' },
  accountRow: {
    borderWidth: 1,
    borderRadius: radius.md,
    padding: 12,
    marginBottom: 6,
    minHeight: 44,
    justifyContent: 'center',
  },
  accountName: { fontSize: 14, fontWeight: '500' },
  fieldLabel: { fontSize: 12, fontWeight: '500', marginBottom: 6 },
  input: {
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: 12,
    minHeight: 48,
    fontSize: 15,
  },
  typeRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 6, marginTop: spacing.sm },
  typeChip: {
    borderWidth: 1,
    borderRadius: 999,
    paddingHorizontal: 14,
    minHeight: 40,
    justifyContent: 'center',
  },
  typeText: { fontSize: 12, fontWeight: '600' },
  rowsHeading: {
    flexDirection: 'row',
    alignItems: 'baseline',
    justifyContent: 'space-between',
    marginBottom: spacing.sm,
    marginTop: spacing.sm,
  },
  rowsTitle: { fontSize: 15, fontWeight: '700' },
  unparseable: { fontSize: 12, marginTop: 6 },
  actions: { marginTop: spacing.md },
  cancel: { marginTop: spacing.sm },
  statRow: { flexDirection: 'row', gap: spacing.md, marginBottom: spacing.sm },
  stat: { flex: 1 },
  statValue: { fontSize: 20, fontWeight: '700' },
  statLabel: { fontSize: 11, marginTop: 2 },
  warnings: { marginTop: spacing.sm, gap: 4 },
});
