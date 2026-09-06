import { useEffect, useMemo, useRef, useState } from 'react';
import {
  ActivityIndicator, Alert, FlatList, Pressable, ScrollView, StyleSheet, Text, TextInput, View,
} from 'react-native';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigation, useRoute, type RouteProp } from '@react-navigation/native';
import type { BottomTabNavigationProp } from '@react-navigation/bottom-tabs';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Button } from '../../components/Button';
import { Card, SectionHeading } from '../../components/Card';
import { OptionPickerModal } from '../../components/OptionPickerModal';
import { UploadProgressPanel, type UploadPanelState } from '../../components/UploadProgressPanel';
import { StagedRowCard } from './StagedRowCard';
import { accountsApi, categoriesApi, importApi, statementImportsApi, type RNFile, type StagingResult } from '../../api/endpoints';
import { PDF_PASSWORD_INVALID, PDF_PASSWORD_REQUIRED } from '../../api/errorCodes';
import { apiErrorCode, isCanceled, toUserMessage } from '../../lib/apiError';
import { fmtCurrency } from '../../lib/format';
import { hapticError, hapticSuccess } from '../../lib/haptics';
import { invalidateFinancialData } from '../../lib/invalidateFinancialData';
import { newIdempotencyKey } from '../../lib/idempotencyKey';
import { expiresInLabel, hasExpired } from '../../lib/importSessionExpiry';
import { useSingleFlight } from '../../lib/useSingleFlight';
import { isPausedCold } from '../../lib/refreshingIndicator';
import {
  buildNewAccountPayload, buildRowPayload, initialAccountForm, initialCategories,
  type NewAccountForm,
} from '../../lib/importPayload';
import {
  applyDecisionToSimilar, beginReview, decide, EMPTY_REVIEW, isUnderReview, setIncluded,
  unresolvedCount, type DuplicateDecision, type RowReview,
} from '../../lib/importReview';
import { matchExistingAccount } from '../../lib/accountMatch';
import { canConfirmImport } from '../../lib/importGate';
import { pickStatement, type StatementFormat } from '../../lib/statementFile';
import { radius, spacing, useTheme } from '../../theme';
import type { AppTabParamList } from '../../navigation/types';
import type { DetectedAccountInfo, ImportSummary, StagedRow, UnparseableRow } from '../../types';

type Step = 'upload' | 'review' | 'summary';
type AccountChoice = 'existing' | 'new';

// How long the "Completed" checkmark stays on screen before the step actually advances -- same
// value and same reasoning as the web app's Import.tsx (UPLOAD_COMPLETE_DWELL_MS): long enough to
// register as a real confirmation, short enough not to feel like a delay.
export const UPLOAD_COMPLETE_DWELL_MS = 900;

export function ImportScreen() {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const queryClient = useQueryClient();
  const route = useRoute<RouteProp<AppTabParamList, 'Import'>>();
  const navigation = useNavigation<BottomTabNavigationProp<AppTabParamList>>();
  const reimportParam = route.params?.reimport;

  const [step, setStep] = useState<Step>('upload');
  const [error, setError] = useState<string | null>(null);
  const [uploadProgress, setUploadProgress] = useState<number | null>(null);
  // True for a brief dwell after a stage call succeeds, before the step actually advances to
  // 'review' -- see celebrateThenAdvance below. Deliberately not derived from uploadProgress: it
  // needs to survive uploadProgress being reset back to null when the dwell ends, or the completed
  // panel would flash back to idle for one frame before 'review' renders.
  const [uploadCompleted, setUploadCompleted] = useState(false);
  const completionTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => () => {
    if (completionTimer.current) clearTimeout(completionTimer.current);
  }, []);
  const [confirming, setConfirming] = useState(false);
  const singleFlight = useSingleFlight();
  const [resumingId, setResumingId] = useState<string | null>(null);
  const [discardingId, setDiscardingId] = useState<string | null>(null);

  // The unfinished-import list. The server has persisted every staged session since ADR-0002 --
  // rows, detected account and the original bytes -- and already exposes list/get/discard. Mobile
  // had all three wired in its API layer with no caller, so an interrupted review looked like total
  // loss when the work was actually sitting on the server the whole time.
  // `retry: false`: this is an optional recovery affordance, and a user with nothing unfinished
  // sees the same empty state either way -- it must never hold up the screen it sits on.
  const unfinishedQ = useQuery({
    queryKey: ['import-sessions'],
    queryFn: () => importApi.listSessions(),
    retry: false,
  });
  // Expired sessions are filtered client-side too, not just server-side: this list is fetched once,
  // so leaving the screen open is enough for a row to go stale, and offering a resume the server
  // would refuse is worse than not offering it.
  const unfinished = (unfinishedQ.data ?? []).filter((sess) => !hasExpired(sess.expiresAt));
  // The key for the CURRENT confirm attempt, minted once and deliberately reused across retries of
  // that same attempt. That is what makes a retry safe rather than duplicating: if the first
  // request never reached the server the key is unused and the retry proceeds; if it arrived and
  // committed, the server refuses the replay instead of importing a second copy; and if it arrived
  // and failed, its claim rolled back in the same transaction, so the retry proceeds too. Cleared
  // after a successful import and by resetToUpload, so a genuinely new re-import gets a new key --
  // re-importing the same statement again later is legitimate and must keep working.
  const attemptKey = useRef<string | null>(null);
  // Aborts the in-flight staging upload. Held in a ref, not state: the Cancel button must reach the
  // CURRENT controller synchronously, and a re-render between press and abort would be enough to
  // send the signal to a stale one.
  const uploadAbort = useRef<AbortController | null>(null);

  const [sessionId, setSessionId] = useState<string | null>(null);
  const [fileFormat, setFileFormat] = useState<StatementFormat | null>(null);
  const [rows, setRows] = useState<StagedRow[]>([]);
  // Include flags and duplicate decisions as ONE value -- see lib/importReview.ts for why they
  // cannot be two pieces of state. Two arrays is the shape that let a row be unticked with the
  // question unasked.
  const [review, setReview] = useState<RowReview>(EMPTY_REVIEW);
  const [chosenCategory, setChosenCategory] = useState<string[]>([]);
  const [unparseableRows, setUnparseableRows] = useState<UnparseableRow[]>([]);
  const [detected, setDetected] = useState<DetectedAccountInfo | null>(null);

  const [accountChoice, setAccountChoice] = useState<AccountChoice>('new');
  const [selectedAccountId, setSelectedAccountId] = useState('');
  const [accountForm, setAccountForm] = useState<NewAccountForm>(initialAccountForm(null));

  const [summary, setSummary] = useState<ImportSummary | null>(null);
  const [categoryPickerFor, setCategoryPickerFor] = useState<number | null>(null);
  // Set only while reviewing a re-import (see the block below). Confirming one goes to a
  // different endpoint and cannot change the account, so this drives both. `password` is carried
  // through only for a protected PDF -- see ReimportParams's own doc comment on why it has to
  // survive past staging now, and StatementImportService.confirmReimport's for the incident that
  // made it necessary.
  const [reimport, setReimport] = useState<{ statementImportId: string; accountId: string; accountName: string; password?: string } | null>(null);
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
  const accountsQ = useQuery({
    queryKey: ['accounts'],
    queryFn: () => accountsApi.list(),
  });
  const existingAccounts = accountsQ.data ?? [];
  // An empty list and a list that failed to load are the same [] -- and here the difference
  // decides where a statement gets filed. With the list unavailable, matchExistingAccount finds
  // nothing, the flow silently defaults to "A new account" with the detected name prefilled, and
  // the "An existing account" chip is disabled, so there is no way back. Confirming then files the
  // statement into a DUPLICATE account and splits one real bank account's history in two. The chip
  // stays disabled (there is genuinely no list to choose from), but saying why is what turns a
  // silent wrong default into a decision the user can make.
  const accountsUnavailable = accountsQ.isError || isPausedCold(accountsQ);

  const includedCount = useMemo(() => review.included.filter(Boolean).length, [review.included]);
  // The gate. Confirm stays disabled while the engine has asked a question nobody has answered --
  // the same rule the web app enforces, so a duplicate cannot be skipped by not looking at it.
  const outstanding = useMemo(
    () => unresolvedCount(rows, review.decisions),
    [rows, review.decisions]
  );
  // Per row, so the card can offer "apply to N identical rows" without each card scanning the whole
  // statement on every render. Counts OTHER rows still unresolved with the same description, which
  // is exactly the set applyDecisionToSimilar would reach.
  const similarUnresolved = useMemo(() => {
    const pending = new Map<string, number>();
    rows.forEach((row, i) => {
      if (isUnderReview(row) && review.decisions[i] === 'unresolved') {
        pending.set(row.description, (pending.get(row.description) ?? 0) + 1);
      }
    });
    return rows.map((row, i) => {
      if (!isUnderReview(row)) return 0;
      const total = pending.get(row.description) ?? 0;
      // Subtract self when this row is itself still unresolved, so the count is always "others".
      return review.decisions[i] === 'unresolved' ? Math.max(total - 1, 0) : total;
    });
  }, [rows, review.decisions]);

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
      password: reimportParam.password,
    });
    // A new re-import target is a new attempt, never a retry of whatever this screen was doing
    // before -- same reasoning as resetToUpload's identical line. Without this, a key kept after a
    // FAILED confirm of a different statement (this ref survives the round trip to History and
    // back, since the tab stays mounted) rides along into this one. claimReimportAttempt on the
    // backend looks the key up by (user, key) alone, with no statementImportId in the lookup, so it
    // reports this never-before-confirmed re-import as already confirmed -- and the mistaken 409
    // then keeps the stale key in place for every retry, since a failed confirm always keeps its
    // key for the attempt it belongs to.
    attemptKey.current = null;
    setFileFormat(null);
    setSessionId(null);
    setRows(reimportParam.staging.rows);
    setReview(beginReview(reimportParam.staging.rows));
    setChosenCategory(initialCategories(reimportParam.staging.rows));
    setUnparseableRows(reimportParam.staging.unparseableRows);
    setDetected(reimportParam.staging.detectedAccount);
    // A re-import IS pinned to an existing account, so say so in the state rather than leaving
    // whatever the previous statement happened to select. confirmImport posts reimport.accountId
    // regardless (this pair is not what the request is built from), but the review screen's
    // re-import branch renders no account picker at all -- so a stale 'existing' with an empty
    // selectedAccountId would otherwise leave the Import button disabled by the account gate below
    // with no control on screen able to satisfy it. Mirrors frontend/src/pages/Import.tsx, which
    // sets the same pair on its own re-import entry for the same reason.
    setAccountChoice('existing');
    setSelectedAccountId(reimportParam.accountId);
    setStep('review');
  }

  function resetToUpload() {
    setStep('upload');
    setError(null);
    // A new import is a new attempt, never a retry of the last one.
    attemptKey.current = null;
    uploadAbort.current = null;
    setUploadProgress(null);
    setSessionId(null);
    setFileFormat(null);
    setRows([]);
    setReview(EMPTY_REVIEW);
    setChosenCategory([]);
    setUnparseableRows([]);
    setDetected(null);
    setSummary(null);
    setAccountForm(initialAccountForm(null));
    // Cleared here as well as being set explicitly on every successful upload: leaving the
    // previous statement's account selected is how a stale choice survives into the next import,
    // and "Import another" runs this between two imports that may well belong to different
    // accounts. upload() always overwrites both, so this is defence in depth rather than the
    // primary guard -- but the primary guard only runs once staging has SUCCEEDED, and an upload
    // that fails or is cancelled returns the user to this screen with the old pair still set.
    setAccountChoice('new');
    setSelectedAccountId('');
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

  /**
   * Everything a staged document contributes to the review step, in one place.
   *
   * Extracted because resume needs byte-for-byte the same hydration a fresh upload does -- the
   * server returns the identical `staging` shape from GET /import/sessions/{id} as it does from
   * stage. Two copies of this would drift, and the half that drifts is the one nobody tests: a
   * resumed import quietly losing its detected account, or defaulting to a different one, files
   * someone's transactions against the wrong balance.
   */
  function hydrateReviewFrom(staging: StagingResult) {
    setRows(staging.rows);
    setReview(beginReview(staging.rows));
    setChosenCategory(initialCategories(staging.rows));
    setUnparseableRows(staging.unparseableRows);
    setDetected(staging.detectedAccount);
    setAccountForm(initialAccountForm(staging.detectedAccount));

    // Default to filing into the existing account this statement's own signals actually point
    // at -- same bank, same account number -- rather than blindly picking the first account in
    // the list. See matchExistingAccount's own comment for the bug this replaced and why
    // anything short of a real match deliberately falls back to "create a new account" (a
    // visible, deletable mistake) rather than guessing at an existing one (a silent, wrong
    // balance).
    //
    // selectedAccountId is cleared, not left alone, when there is no match. Before this change
    // the 'new' branch was only reachable with zero existing accounts, so a stale id could
    // never coexist with a populated account list; now it can, and leaving it set would render
    // the previous import's account as the highlighted, apparently-considered choice the moment
    // the user tapped "An existing account".
    const matched = matchExistingAccount(staging.detectedAccount, existingAccounts);
    setAccountChoice(matched ? 'existing' : 'new');
    setSelectedAccountId(matched ? matched.id : '');
  }

  /** Reopen an unfinished import at the review step, exactly where it was staged. */
  async function resumeSession(id: string) {
    setError(null);
    setResumingId(id);
    try {
      const res = await importApi.getSession(id);
      setSessionId(res.sessionId);
      hydrateReviewFrom(res.staging);
      setStep('review');
    } catch (e) {
      // The most likely failure is that it expired or was confirmed elsewhere between the list
      // being fetched and this tap, so refresh the list rather than leaving a row that cannot work.
      setError(toUserMessage(e, "Couldn't reopen that import — it may have expired."));
      void unfinishedQ.refetch();
    } finally {
      setResumingId(null);
    }
  }

  function confirmDiscardSession(sess: { id: string; fileName: string }) {
    Alert.alert(
      'Discard this import?',
      `"${sess.fileName}" and everything reviewed so far will be removed. The statement itself isn't affected.`,
      [
        { text: 'Cancel', style: 'cancel' },
        { text: 'Discard', style: 'destructive', onPress: () => void discardUnfinished(sess.id) },
      ]
    );
  }

  async function discardUnfinished(id: string) {
    setError(null);
    setDiscardingId(id);
    try {
      await importApi.discardSession(id);
      await unfinishedQ.refetch();
    } catch (e) {
      setError(toUserMessage(e, 'Could not discard that import.'));
    } finally {
      setDiscardingId(null);
    }
  }

  // Holds the panel on 'completed' for UPLOAD_COMPLETE_DWELL_MS before running `advance` (a
  // setStep call) -- the pause that makes the checkmark a real, noticeable state rather than a
  // single frame between "100%" and the review screen. uploadProgress is reset here, not in
  // upload()'s finally, so the panel doesn't fall back to 'idle' for a frame while `uploadCompleted`
  // is still true (see that state's own doc comment).
  function celebrateThenAdvance(advance: () => void) {
    // Defensive, not currently reachable: every caller of upload() already refuses to run while
    // `uploading` (which covers uploadCompleted too) is true, so this can't yet fire twice before
    // the first timer completes. Guards against that invariant quietly breaking later, rather than
    // leaking the earlier timer and calling `advance` twice.
    if (completionTimer.current) clearTimeout(completionTimer.current);
    setUploadCompleted(true);
    completionTimer.current = setTimeout(() => {
      setUploadCompleted(false);
      setUploadProgress(null);
      advance();
    }, UPLOAD_COMPLETE_DWELL_MS);
  }

  async function upload(file: RNFile, isPdf: boolean, password: string | undefined) {
    setError(null);
    setUploadProgress(0);
    // Set once the stage call succeeds, so the finally block below skips its usual reset and
    // leaves uploadProgress/uploadCompleted for celebrateThenAdvance to clear itself.
    let holdForCompletion = false;
    const controller = new AbortController();
    uploadAbort.current = controller;
    try {
      const res = isPdf
        ? await importApi.stagePdf(file, setUploadProgress, password, controller.signal)
        : await importApi.stageCsv(file, setUploadProgress, controller.signal);

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
          'This statement covers more than one account. Multi-account statements can only be imported from the Fynora web app for now.'
        );
        setUploadProgress(null);
        return;
      }

      const staging = (res as { staging: NonNullable<typeof res.staging> }).staging;
      hydrateReviewFrom(staging);
      holdForCompletion = true;
      celebrateThenAdvance(() => setStep('review'));
    } catch (e) {
      // A cancel is the user getting what they asked for, not a failure -- no error banner. Checked
      // before everything else because a cancelled request otherwise reads as a network error
      // (no response, see isCanceled's own comment) and would print "Could not read that statement."
      if (isCanceled(e)) return;
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
      uploadAbort.current = null;
      if (!holdForCompletion) setUploadProgress(null);
    }
  }

  /** Abort an in-flight staging upload. Safe to call when nothing is in flight. */
  function cancelUpload() {
    uploadAbort.current?.abort();
  }

  async function confirmImport() {
    if (!reimport && !sessionId) return;
    // useSingleFlight, not just the `confirming` flag: that flag is STATE, so two presses
    // dispatched in the same frame both read `confirming === false` and both fire. A ref closes
    // that window synchronously. It is the client half of the fix -- the server half
    // (V133/claimReimportAttempt) is what covers the paths no client-side guard can reach: a
    // retried request whose response was lost, or a second device.
    await singleFlight(async () => {
      await runConfirm();
    });
  }

  async function runConfirm() {
    setConfirming(true);
    setError(null);
    if (attemptKey.current === null) attemptKey.current = newIdempotencyKey();
    try {
      // A re-import goes to its own endpoint and is pinned to the account the statement already
      // belongs to -- re-importing into a DIFFERENT account would defeat the point of replaying
      // this statement rather than importing a fresh one.
      const result = reimport
        ? await statementImportsApi.confirmReimport(reimport.statementImportId, {
            rows: buildRowPayload(rows, review, chosenCategory),
            existingAccountId: reimport.accountId,
            statementOpeningBalance: detected?.openingBalance ?? null,
            statementClosingBalance: detected?.closingBalance ?? null,
            password: reimport.password,
            idempotencyKey: attemptKey.current ?? undefined,
          })
        : await importApi.confirm({
            sessionId: sessionId!,
            rows: buildRowPayload(rows, review, chosenCategory),
            existingAccountId: accountChoice === 'existing' ? selectedAccountId : null,
            newAccount: accountChoice === 'new' ? buildNewAccountPayload(accountForm, detected) : null,
            statementOpeningBalance: detected?.openingBalance ?? null,
            statementClosingBalance: detected?.closingBalance ?? null,
          });
      setSummary(result);
      setStep('summary');
      // Only on success: a failed attempt keeps its key so a retry is recognised as the SAME
      // attempt rather than becoming a second one the server would happily import.
      attemptKey.current = null;
      invalidateFinancialData(queryClient);
      // The moment the import actually lands, not when the button is pressed -- firing before
      // the request resolves would celebrate a network failure too.
      hapticSuccess();
    } catch (e) {
      setError(toUserMessage(e, 'Could not complete the import.'));
      hapticError();
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
    // Same priority order the screen already had: an in-flight (or just-finished) upload takes
    // over the card's content regardless of whether a PDF password is pending, and the password
    // panel only reappears once neither is true (e.g. after a wrong password bounced it back).
    const panelState: UploadPanelState = uploadCompleted ? 'completed' : uploading ? 'uploading' : 'idle';
    const showPasswordPanel = !uploading && !uploadCompleted && !!pendingPdf;
    return (
      <View style={[styles.flex, { backgroundColor: c.bg, paddingTop: insets.top + spacing.md }]}>
        {/* ScrollView, not the plain View this used to be: the unfinished-import list below is
            variable-length, and on a small screen a couple of entries pushed "Choose a file" off
            the bottom with no way to reach it. */}
        <ScrollView contentContainerStyle={styles.padded} keyboardShouldPersistTaps="handled">
          {header}
          <Card>
            <SectionHeading title="Import a statement" />
            <Text style={[styles.body, { color: c.muted }]}>
              Choose a CSV or PDF statement from your bank. You'll review every transaction before
              anything is added.
            </Text>

            {showPasswordPanel ? null : (
              <>
                <UploadProgressPanel
                  state={panelState}
                  progress={uploadProgress ?? 0}
                  idle={<Button label="Choose a file" onPress={handlePick} />}
                />
                {/* The only way out of this screen while an upload is in flight. It matters most
                    on the request that can never time out on its own (see toUploadProgressConfig):
                    on a dead connection the bar simply freezes, and without this the user is stuck
                    watching it.
                    Bug fix: gated on panelState, not `uploading` -- uploadProgress (what `uploading`
                    reads) isn't reset to null until celebrateThenAdvance's dwell timer fires, so
                    `uploading` stays true for the whole 'completed' checkmark too. This button was
                    sitting there through the entire dwell, doing nothing (cancelUpload's abort
                    controller is already null by then -- upload() already succeeded). */}
                {panelState === 'uploading' && <Button label="Cancel upload" variant="link" onPress={cancelUpload} />}
              </>
            )}
            {showPasswordPanel && pendingPdf && (
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
                  // The bank's password for one document, not a Fynora credential -- it doesn't
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
            )}
          </Card>

          {/* Only when there is something to resume. An empty state here would be a permanent
              reminder of a feature that has nothing to offer, on the screen a first-time user
              sees before they have ever imported anything. */}
          {unfinished.length > 0 ? (
            <Card style={styles.unfinishedCard}>
              <SectionHeading title="Continue a previous import" />
              <Text style={[styles.body, { color: c.muted }]}>
                These statements were uploaded and read, but never finished. Picking one up puts you
                back on the review step — nothing is re-uploaded.
              </Text>
              {unfinished.map((sess) => (
                <View
                  key={sess.id}
                  style={[styles.unfinishedRow, { borderBottomColor: c.border }]}
                >
                  <View style={styles.unfinishedMain}>
                    <Text style={[styles.unfinishedName, { color: c.ink }]} numberOfLines={1}>
                      {sess.fileName}
                    </Text>
                    <Text style={[styles.unfinishedMeta, { color: c.mutedInk }]} numberOfLines={1}>
                      {sess.rowCount} {sess.rowCount === 1 ? 'transaction' : 'transactions'} ·{' '}
                      {expiresInLabel(sess.expiresAt)}
                    </Text>
                  </View>
                  {resumingId === sess.id ? (
                    <ActivityIndicator size="small" color={c.muted} />
                  ) : (
                    <View style={styles.unfinishedActions}>
                      <Pressable
                        onPress={() => void resumeSession(sess.id)}
                        disabled={discardingId === sess.id}
                        hitSlop={8}
                        accessibilityRole="button"
                        accessibilityLabel={`Resume import of ${sess.fileName}`}
                      >
                        <Text style={[styles.unfinishedAction, { color: c.primary }]}>Resume</Text>
                      </Pressable>
                      <Pressable
                        onPress={() => confirmDiscardSession(sess)}
                        disabled={discardingId === sess.id}
                        hitSlop={8}
                        accessibilityRole="button"
                        accessibilityLabel={`Discard import of ${sess.fileName}`}
                      >
                        <Text style={[styles.unfinishedAction, { color: c.danger }]}>
                          {discardingId === sess.id ? 'Discarding…' : 'Discard'}
                        </Text>
                      </Pressable>
                    </View>
                  )}
                </View>
              ))}
            </Card>
          ) : null}
        </ScrollView>
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
              {/* Track C/C6. Depended on C4's Ledger filters existing at all -- without them this
                  would land on the whole, unfiltered ledger, no more useful than the Transactions
                  tab a user could already reach on their own. account can genuinely be null (see
                  ImportSummary's own type); the period alone still narrows down to this import
                  when it is. */}
              <View style={styles.viewInLedger}>
                <Button
                  label="View in Ledger"
                  variant="link"
                  onPress={() => {
                    const period = summary.statementPeriodStart && summary.statementPeriodEnd
                      ? ` · ${summary.statementPeriodStart} to ${summary.statementPeriodEnd}`
                      : '';
                    navigation.navigate('Transactions', {
                      filters: {
                        accountId: summary.account?.id,
                        dateFrom: summary.statementPeriodStart ?? undefined,
                        dateTo: summary.statementPeriodEnd ?? undefined,
                        label: `${summary.account?.name ?? 'This import'}${period}`,
                        nonce: Date.now(),
                      },
                    });
                  }}
                />
              </View>
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

              {accountsUnavailable ? (
                <Text style={[styles.helpText, { color: c.danger }]}>
                  Couldn&apos;t load your existing accounts, so this can only be filed as a new one.
                  If this statement belongs to an account you already have, go back and retry rather
                  than importing it here — filing it as new would split that account&apos;s history.
                </Text>
              ) : null}

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
                    accessibilityLabel="Account name"
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
            included={review.included[index]}
            category={chosenCategory[index]}
            onToggleIncluded={() =>
              setReview((prev) => setIncluded(prev, index, !prev.included[index]))
            }
            onPressCategory={() => setCategoryPickerFor(index)}
            decision={review.decisions[index]}
            onDecide={(d: DuplicateDecision) => setReview((prev) => decide(rows, prev, index, d))}
            similarUnresolved={similarUnresolved[index] ?? 0}
            onApplyToSimilar={() => setReview((prev) => applyDecisionToSimilar(rows, prev, index))}
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
              {/* Says which question is outstanding, not just that one is. "Confirm is disabled"
                  with no reason is the shape that makes people tap it repeatedly; the count tells
                  them how much is left and the rows carry the questions themselves. */}
              {outstanding > 0 ? (
                <Text style={[styles.gateNotice, { color: c.warningInk, backgroundColor: c.warningBg }]}>
                  {outstanding} possible duplicate{outstanding === 1 ? '' : 's'} still need
                  {outstanding === 1 ? 's' : ''} an answer above before this can be imported.
                </Text>
              ) : null}
              <Button
                label={`Import ${includedCount} transaction${includedCount === 1 ? '' : 's'}`}
                onPress={confirmImport}
                loading={confirming}
                // Every reason this button may refuse lives in canConfirmImport, with tests --
                // see that function's own comment for why it is not inline here.
                disabled={
                  !canConfirmImport({
                    includedCount,
                    outstanding,
                    isReimport: reimport !== null,
                    accountChoice,
                    selectedAccountId,
                  })
                }
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
  unfinishedCard: { marginTop: spacing.md },
  unfinishedRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: 12,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  unfinishedMain: { flex: 1, marginRight: spacing.sm },
  unfinishedName: { fontSize: 14, fontWeight: '600' },
  unfinishedMeta: { fontSize: 12, marginTop: 2 },
  unfinishedActions: { flexDirection: 'row', alignItems: 'center', gap: spacing.md },
  unfinishedAction: { fontSize: 13, fontWeight: '600' },
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
  gateNotice: {
    fontSize: 12,
    fontWeight: '500',
    padding: 10,
    borderRadius: radius.md,
    marginBottom: spacing.sm,
    overflow: 'hidden',
  },
  cancel: { marginTop: spacing.sm },
  viewInLedger: { marginTop: spacing.sm },
  statRow: { flexDirection: 'row', gap: spacing.md, marginBottom: spacing.sm },
  stat: { flex: 1 },
  statValue: { fontSize: 20, fontWeight: '700' },
  statLabel: { fontSize: 11, marginTop: 2 },
  warnings: { marginTop: spacing.sm, gap: 4 },
});
