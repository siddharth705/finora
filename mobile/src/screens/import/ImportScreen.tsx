import { useMemo, useState } from 'react';
import {
  FlatList, Pressable, StyleSheet, Text, TextInput, View,
} from 'react-native';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useRoute, type RouteProp } from '@react-navigation/native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Button } from '../../components/Button';
import { Card, SectionHeading } from '../../components/Card';
import { OptionPickerModal } from '../../components/OptionPickerModal';
import { StagedRowCard } from './StagedRowCard';
import { accountsApi, categoriesApi, importApi, statementImportsApi, type RNFile } from '../../api/endpoints';
import { PDF_PASSWORD_INVALID, PDF_PASSWORD_REQUIRED } from '../../api/errorCodes';
import { apiErrorCode, toUserMessage } from '../../lib/apiError';
import { fmtCurrency } from '../../lib/format';
import { invalidateFinancialData } from '../../lib/invalidateFinancialData';
import {
  buildNewAccountPayload, buildRowPayload, initialAccountForm, initialCategories, initialInclusion,
  type NewAccountForm,
} from '../../lib/importPayload';
import { pickStatement, type StatementFormat } from '../../lib/statementFile';
import { radius, spacing, useTheme } from '../../theme';
import type { AppTabParamList } from '../../navigation/types';
import type { DetectedAccountInfo, ImportSummary, StagedRow, UnparseableRow } from '../../types';

type Step = 'upload' | 'review' | 'summary';
type AccountChoice = 'existing' | 'new';

export function ImportScreen() {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const queryClient = useQueryClient();
  const route = useRoute<RouteProp<AppTabParamList, 'Import'>>();
  const reimportParam = route.params?.reimport;

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
  // Set only while reviewing a re-import (see the block below). Confirming one goes to a
  // different endpoint and cannot change the account, so this drives both.
  const [reimport, setReimport] = useState<{ statementImportId: string; accountId: string; accountName: string } | null>(null);
  // The nonce of the re-import already loaded into the review step; see the block below.
  const [consumedReimportNonce, setConsumedReimportNonce] = useState<number | null>(null);

  // A chosen PDF, held between picking and uploading so the optional password can be typed first,
  // and so a wrong password retries against the SAME file instead of sending the user back to the
  // document picker. CSV keeps its original pick-and-go behaviour -- there's nothing to unlock.
  const [pendingPdf, setPendingPdf] = useState<RNFile | null>(null);
  const [pdfPassword, setPdfPassword] = useState('');
  // Which of the two backend password outcomes we last saw, or null before we've tried.
  const [passwordState, setPasswordState] = useState<'required' | 'invalid' | null>(null);

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

  /**
   * Arriving from "Re-import" on the Statement History screen: the rows were already staged there,
   * so this skips straight to review rather than asking for a file. The account is fixed to the one
   * the statement already belongs to, which is why there is no account choice to make.
   *
   * Adjusted during render rather than in an effect -- React's documented pattern for "reset state
   * when an input changes", and it avoids rendering the upload step for one frame before the
   * review step replaces it.
   *
   * Keyed on the nonce, not the statement id, so re-importing the SAME statement twice is still
   * seen as two separate arrivals. A tab's params outlive a visit, so without a key of some sort
   * this would re-enter the same re-import on every later tap of the Import tab.
   */
  if (reimportParam && reimportParam.nonce !== consumedReimportNonce) {
    setConsumedReimportNonce(reimportParam.nonce);
    setReimport({
      statementImportId: reimportParam.statementImportId,
      accountId: reimportParam.accountId,
      accountName: reimportParam.accountName,
    });
    setFileFormat(null);
    setSessionId(null);
    setRows(reimportParam.staging.rows);
    setIncluded(initialInclusion(reimportParam.staging.rows));
    setChosenCategory(initialCategories(reimportParam.staging.rows));
    setUnparseableRows(reimportParam.staging.unparseableRows);
    setDetected(reimportParam.staging.detectedAccount);
    setStep('review');
  }

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
    setPendingPdf(null);
    setPdfPassword('');
    setPasswordState(null);
    setReimport(null);
  }

  /**
   * A CSV goes straight up, as it always has. A PDF stops at the password card first: most Indian
   * banks e-mail statements password-protected, so asking up front turns the common case into one
   * upload rather than an upload, a rejection, and a second upload.
   */
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

    setFileFormat(picked.format);
    if (picked.format === 'PDF') {
      setPendingPdf(picked.file);
      setPdfPassword('');
      setPasswordState(null);
      return;
    }
    await upload(picked.file, false, undefined);
  }

  async function upload(file: RNFile, isPdf: boolean, password: string | undefined) {
    setError(null);
    setUploadProgress(0);
    try {
      const res = isPdf
        ? await importApi.stagePdf(file, setUploadProgress, password)
        : await importApi.stageCsv(file, setUploadProgress);

      setSessionId(res.sessionId);
      // The document opened, so the password has done its whole job -- drop it and the file.
      setPendingPdf(null);
      setPdfPassword('');
      setPasswordState(null);

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
      const code = apiErrorCode(e);
      if (code === PDF_PASSWORD_REQUIRED || code === PDF_PASSWORD_INVALID) {
        // Not a read failure and not shown as one -- the file is fine, it just hasn't been opened
        // yet. The card stays put with this same file so the retry is one field and one tap.
        setPasswordState(code === PDF_PASSWORD_INVALID ? 'invalid' : 'required');
        setPendingPdf(file);
      } else {
        setError(toUserMessage(e, 'Could not read that statement.'));
      }
    } finally {
      setUploadProgress(null);
    }
  }

  async function confirmImport() {
    if (!reimport && !sessionId) return;
    setConfirming(true);
    setError(null);
    try {
      // A re-import goes to its own endpoint and is pinned to the account the statement already
      // belongs to -- re-importing into a DIFFERENT account would defeat the point of replaying
      // this statement rather than importing a fresh one.
      const result = reimport
        ? await statementImportsApi.confirmReimport(reimport.statementImportId, {
            rows: buildRowPayload(rows, included, chosenCategory),
            existingAccountId: reimport.accountId,
            statementOpeningBalance: detected?.openingBalance ?? null,
            statementClosingBalance: detected?.closingBalance ?? null,
          })
        : await importApi.confirm({
            sessionId: sessionId!,
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
            ) : pendingPdf ? (
              <View style={styles.passwordWrap} testID="pdf-password-panel">
                <Text style={[styles.body, { color: c.ink, fontWeight: '600' }]} numberOfLines={2}>
                  {pendingPdf.name}
                </Text>
                <Text style={[styles.fieldLabel, { color: c.ink }]}>Statement password (optional)</Text>
                <TextInput
                  value={pdfPassword}
                  onChangeText={setPdfPassword}
                  placeholder="Leave blank if the file isn't protected"
                  placeholderTextColor={c.muted}
                  secureTextEntry
                  autoCapitalize="none"
                  autoCorrect={false}
                  // The bank's password for one document, not a Finora credential -- it doesn't
                  // belong in the OS keychain alongside real logins, and it changes every month.
                  autoComplete="off"
                  textContentType="none"
                  accessibilityLabel="Statement password"
                  style={[styles.input, { color: c.ink, borderColor: c.border, backgroundColor: c.inputBg }]}
                />
                <Text
                  style={[styles.helpText, { color: passwordState === 'invalid' ? c.danger : c.muted }]}
                >
                  {passwordState === 'invalid'
                    ? "That password didn't open this statement — check it and try again."
                    : passwordState === 'required'
                      ? 'This statement is password protected. Enter the password your bank uses for it.'
                      : 'Many banks protect statements with a password — often a mix of your name, PAN, date of birth or account number. Check the email it came in.'}
                </Text>
                <Button
                  label="Upload statement"
                  onPress={() => void upload(pendingPdf, true, pdfPassword || undefined)}
                />
                <Button
                  label="Choose a different file"
                  variant="link"
                  onPress={() => {
                    setPendingPdf(null);
                    setPdfPassword('');
                    setPasswordState(null);
                    setError(null);
                  }}
                />
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

            {reimport ? (
              <Card style={styles.section}>
                <SectionHeading title="File into" />
                {/* No choice to offer: a re-import replays this statement into the account it
                    already belongs to. Stated rather than hidden, so it is obvious where these
                    rows are going. */}
                <Text style={[styles.body, { color: c.ink }]}>{reimport.accountName}</Text>
                <Text style={[styles.body, { color: c.muted }]}>
                  The account this statement was originally imported into. Duplicate detection below
                  runs against everything already on the books, including this statement&apos;s own
                  earlier transactions.
                </Text>
              </Card>
            ) : (
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
            )}

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

      <OptionPickerModal
        visible={categoryPickerFor !== null}
        title="Category"
        options={categories}
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
  // gap is smaller than the field label's own marginBottom, so the label stays visually attached
  // to its input rather than floating midway between it and the filename above.
  passwordWrap: { marginTop: spacing.sm, gap: spacing.sm },
  helpText: { fontSize: 12, lineHeight: 17 },
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
