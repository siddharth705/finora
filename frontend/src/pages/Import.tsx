import { useEffect, useRef, useState, type Dispatch, type SetStateAction } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion';
import { useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { CheckCircle2, UploadCloud, AlertTriangle, Clock, FileText, FileSpreadsheet, Trash2, RefreshCw } from 'lucide-react';
import { importApi, importJobsApi, statementImportsApi, categoriesApi, accountsApi, type StagingResult } from '../api/endpoints';
import { PDF_PASSWORD_REQUIRED, PDF_PASSWORD_INVALID, IMPORT_SESSION_ALREADY_CONFIRMED } from '../api/errorCodes';
import { importFailureMessage } from '../api/importFailureMessages';
import { BankLogo } from '../components/BankLogo';
import { MaskedAccountNumber } from '../components/MaskedAccountNumber';
import { VerificationPanel } from '../components/VerificationPanel';
import { matchExistingAccount } from '../lib/accountMatch';
import { isLikelyMatch } from '../lib/holderNameMatcher';
import { DuplicateReview } from '../components/DuplicateReview';
import { ImportProgress } from '../components/ImportProgress';
import { ImportTimeline } from '../components/ImportTimeline';
import {
  EMPTY_REVIEW,
  applyDecisionToSimilar,
  beginReview,
  decide,
  decideAllUnresolved,
  isUnconfirmedGuess,
  setIncluded,
  toConfirmedRows,
  unresolvedCount,
  type DuplicateDecision,
  type RowReview,
} from '../lib/importReview';
import { toNewAccountPayload } from '../lib/newAccountPayload';
import { Button, ConfirmDialog, IconButton } from '../design-system';
import type { ImportNavState } from '../lib/importNavState';
import { useAuth } from '../context/AuthContext';
import type { Account, DetectedAccountInfo, VerificationReport, ImportSummary, StagedAccountSection, StagedRow, SupersedeResult, UnparseableRow } from '../types';
import { formatDate, formatDateDDMMMYYYY } from '../utils/date';

type Step = 'upload' | 'review' | 'summary';
type AccountChoice = 'existing' | 'new';

// Per-account review state for the multi-account case (a PDF whose upload detected more than one
// account section, e.g. an HSBC-style composite statement) -- one of these per detected
// StagedAccountSection, holding exactly the same fields the single-account path already tracks as
// flat top-level state, just namespaced per section instead.
//
// `review` is the field this shape was missing, and the whole reason the multi-account path kept
// WI5's pre-WI5 behaviour: with only a flat `included: boolean[]` here, a flagged row could be
// unticked but the user's answer had nowhere to live, so there was no answer and the untick was
// silent. It is now the same RowReview the single-account path holds, produced by the same
// beginReview() -- one review per detected account, because a decision about a row in the savings
// section says nothing about a row in the credit-card section.
interface SectionState {
  detectedAccount: DetectedAccountInfo;
  rows: StagedRow[];
  review: RowReview;
  chosenCategory: string[];
  accountChoice: AccountChoice;
  selectedAccountId: string;
  newName: string;
  newType: Account['accountType'];
  newOpeningBalance: string;
  newCreditLimit: string;
  newDueDate: string;
  // "Never lose information" (see the engineering principles doc) -- rows the backend couldn't
  // parse into a transaction, shown for transparency, never confirmable into the ledger.
  unparseableRows: UnparseableRow[];
  // Per section, never merged -- one section of a composite statement can verify while another
  // does not, and a combined verdict would hide that.
  verification: VerificationReport | null;
}

function initialSectionState(section: StagedAccountSection, existingAccounts: Account[]): SectionState {
  const detected = section.detectedAccount;
  // Per section, not per file. This defaulted every section of a composite statement to
  // existingAccounts[0] -- so an HSBC file bundling a savings section and a credit-card section
  // proposed merging both into the same account, which is the one shape of this bug that is
  // wrong even when the user has exactly one account. See matchExistingAccount.
  const match = matchExistingAccount(detected, existingAccounts);
  return {
    detectedAccount: detected,
    rows: section.rows,
    // Was `section.rows.map((r) => !r.likelyDuplicate)` -- the silent filter WI5 removed from the
    // single-account path and left here. beginReview() produces the include flags and the
    // decisions together, so a row can no longer be unticked without also being unanswered.
    review: beginReview(section.rows),
    chosenCategory: section.rows.map((r) => r.suggestedCategory),
    accountChoice: match ? 'existing' : 'new',
    selectedAccountId: match ? match.id : '',
    newName: detected.suggestedName,
    newType: detected.suggestedAccountType,
    newOpeningBalance: detected.openingBalance != null ? String(detected.openingBalance) : '',
    newCreditLimit: detected.creditLimit != null ? String(detected.creditLimit) : '',
    newDueDate: detected.paymentDueDate ?? '',
    unparseableRows: section.unparseableRows,
    verification: section.verification ?? null,
  };
}

function fmt(n: number | null) {
  if (n === null || n === undefined) return '—';
  // Negative amounts must render as "-₹500", not "₹-500".
  return (n < 0 ? '-₹' : '₹') + Math.round(Math.abs(n)).toLocaleString('en-IN');
}

// Shared between confirmImport() and confirmMultiImport() -- the Dashboard (and everywhere else
// fed by this data) should refresh automatically once an import completes, regardless of whether
// it went into one account or several. Mirrors StatementHistory.tsx's own invalidation set.
function invalidateImportRelatedQueries(queryClient: QueryClient) {
  void queryClient.invalidateQueries({ queryKey: ['dashboard-summary'] });
  void queryClient.invalidateQueries({ queryKey: ['accounts'] });
  void queryClient.invalidateQueries({ queryKey: ['transactions'] });
  void queryClient.invalidateQueries({ queryKey: ['recent-transactions'] });
  void queryClient.invalidateQueries({ queryKey: ['goals'] });
  void queryClient.invalidateQueries({ queryKey: ['insights'] });
  void queryClient.invalidateQueries({ queryKey: ['statement-imports'] });
  void queryClient.invalidateQueries({ queryKey: ['budgets'] });
  void queryClient.invalidateQueries({ queryKey: ['report-months'] });
  void queryClient.invalidateQueries({ queryKey: ['report'] });
}

export default function Import() {
  const fileInput = useRef<HTMLInputElement>(null);
  const navigate = useNavigate();
  const location = useLocation();
  const queryClient = useQueryClient();
  const { fullName } = useAuth();

  // "Re-import Statement" (from the Statement History page) lands here with the original file's
  // staging result already computed server-side — see StatementImportService.reimport(). There's
  // no browser File object in this case (the bytes never left the server), so this page skips
  // straight to the review step, locked to the account the statement already belongs to.
  //
  // One cast, discriminated by the `kind` tag every navigate() call site below sets -- not a
  // per-shape field-existence guess. That guessing approach caused a real bug once (a blind
  // truthiness cast on ReimportNavState alone was truthy for ANY non-null state, so arriving with
  // only `{resumeSessionId}` still took the reimport branch and crashed reading `.staging.rows`
  // off a value that was never a ReimportResult), and a field-existence check per shape is the
  // same class of guess with an extra step. A third shape (RetryFailedImportNavState) made
  // copy-pasting that check again the wrong call.
  const navState = (location.state as ImportNavState | null) ?? null;
  const reimportState = navState?.kind === 'reimport' ? navState : null;
  const resumeState = navState?.kind === 'resume' ? navState : null;
  const retryState = navState?.kind === 'retry' ? navState : null;

  const [step, setStep] = useState<Step>('upload');
  // "disables both motion props entirely rather than just shrinking them" -- same convention
  // Button.tsx/QuickActionCard.tsx already established: an empty props object means the step's
  // motion.div mounts/unmounts in its final state with no transition at all, and AnimatePresence
  // never delays the unmount waiting on an exit animation that isn't there.
  const prefersReducedMotion = useReducedMotion();
  const stepMotionProps = prefersReducedMotion
    ? {}
    : {
        initial: { opacity: 0, y: 8 },
        animate: { opacity: 1, y: 0 },
        exit: { opacity: 0, y: -8 },
        transition: { duration: 0.18, ease: 'easeOut' as const },
      };
  const [error, setError] = useState<string | null>(null);
  // Sprint 4 item 22. Whether the CURRENT error banner is one the user can fix themselves --
  // distinct from `error` itself so the banner can pick warning (ACTION_REQUIRED) vs danger
  // (FAILED) coloring, matching ImportTimeline's identical distinction for the async path. Never
  // set directly -- showError()/clearError() below are the only two ways `error` changes, so this
  // can never go stale from a PREVIOUS error (e.g. an ACTION_REQUIRED parse failure) while a new,
  // unrelated one (network failure, a validation message, a discard failure) is being shown.
  const [errorActionRequired, setErrorActionRequired] = useState(false);

  function showError(message: string, actionRequired = false) {
    setError(message);
    setErrorActionRequired(actionRequired);
  }
  function clearError() {
    setError(null);
    setErrorActionRequired(false);
  }

  // The queued import currently being watched, if this deployment queues them at all.
  //
  // `asyncAvailable` is asked once on mount rather than assumed: the queue is opt-in per
  // deployment, and the alternative — send the upload and read the 503 — would push the whole file
  // across the network before finding out, then push it again on the synchronous path.
  const [jobId, setJobId] = useState<string | null>(null);
  const [asyncAvailable, setAsyncAvailable] = useState(false);

  // Staged rows. `review` holds the include flags and the duplicate decisions as one value --
  // see lib/importReview.ts for why they are not two pieces of state.
  const [rows, setRows] = useState<StagedRow[]>([]);
  const [review, setReview] = useState<RowReview>(EMPTY_REVIEW);
  const [chosenCategory, setChosenCategory] = useState<string[]>([]);
  const [categories, setCategories] = useState<string[]>([]);
  // "Never lose information" -- rows the backend couldn't parse, shown for transparency.
  const [unparseableRows, setUnparseableRows] = useState<UnparseableRow[]>([]);

  // Target account: an existing one, or a new one built from what the statement told us
  const [existingAccounts, setExistingAccounts] = useState<Account[]>([]);
  const [detectedAccount, setDetectedAccount] = useState<DetectedAccountInfo | null>(null);
  const [verification, setVerification] = useState<VerificationReport | null>(null);
  const [accountChoice, setAccountChoice] = useState<AccountChoice>('new');
  const [selectedAccountId, setSelectedAccountId] = useState('');
  const [newName, setNewName] = useState('');
  const [newType, setNewType] = useState<Account['accountType']>('SAVINGS');
  const [newOpeningBalance, setNewOpeningBalance] = useState('');
  const [newCreditLimit, setNewCreditLimit] = useState('');
  const [newDueDate, setNewDueDate] = useState('');

  const [summary, setSummary] = useState<ImportSummary | null>(null);
  const [multiSummary, setMultiSummary] = useState<ImportSummary[] | null>(null);
  const [confirming, setConfirming] = useState(false);

  // docs/proposals/account-ownership-intelligence-proposal.md §3.1. A client-side pre-check only
  // -- it decides whether to show the "Statement Check" dialog before confirming; the backend
  // independently computes and persists the authoritative OwnershipMatchStatus at confirm time
  // (see OwnershipMatchService), which is the source of truth for audit purposes. Reset per
  // upload via the effects that already clear detectedAccount, so a second file in the same
  // session gets its own check.
  const [ownershipWarningOpen, setOwnershipWarningOpen] = useState(false);
  const [ownershipWarningAcknowledged, setOwnershipWarningAcknowledged] = useState(false);

  // Set only for a multi-account PDF upload (see SectionState above) -- null the rest of the
  // time, and the single flat rows/detectedAccount/etc. state above is what's used instead.
  const [multiSections, setMultiSections] = useState<SectionState[] | null>(null);

  // 0-100 while a file is uploading, null otherwise -- purely the network-transfer portion (see
  // ProgressCallback's own doc comment in endpoints.ts), so 100% means "processing," not "done."
  const [uploadProgress, setUploadProgress] = useState<number | null>(null);

  // A chosen PDF, held here between selection and upload so the optional password can be typed
  // first, and so a wrong password can be retried against the SAME file without re-picking it.
  // CSV keeps its original one-action upload -- it has no password to ask about, and adding a
  // step there would slow the common case down for nothing.
  const [pendingPdf, setPendingPdf] = useState<File | null>(null);
  const [pdfPassword, setPdfPassword] = useState('');
  // Which of the two backend password outcomes we last saw, or null before we've tried. Drives
  // the copy in the password panel; see PDF_PASSWORD_REQUIRED/INVALID in endpoints.ts.
  const [passwordState, setPasswordState] = useState<'required' | 'invalid' | null>(null);
  const passwordInput = useRef<HTMLInputElement>(null);

  // ADR-0002: the backend now persists the staged file/rows server-side (ImportSession), keyed
  // by this id -- confirmImport() sends it instead of re-uploading the original file a second
  // time, which is what used to require holding onto the File object in state after staging.
  const [sessionId, setSessionId] = useState<string | null>(null);
  // Purely cosmetic — shown as a small badge in the review step so it's obvious at a glance which
  // path staged this session, same spirit as the "Detected {bank}" callout below.
  const [fileFormat, setFileFormat] = useState<'CSV' | 'PDF' | null>(null);

  // "Continue previous import" (Premium Import Reliability v1, §3) -- a staged session the user
  // never confirmed, e.g. a closed tab or a lost connection between upload and confirm. The
  // backend already scopes this to the caller's own, active (not expired, not yet confirmed)
  // sessions (ImportSessionService.listActiveSessions), so there is no ownership check to add
  // here -- only whether to show what it returns.
  const [discardingSessionId, setDiscardingSessionId] = useState<string | null>(null);
  // "Continue Import" had no in-flight feedback at all -- resumeSession() awaits
  // importApi.getSession() before setStep('review') ever fires, so the button sat inert with no
  // spinner, no disabled state, nothing, for however long that fetch took.
  //
  // A Set, not a single id: unlike discardingSessionId (only ever one at a time, since it's gated
  // behind a single ConfirmDialog), every row's Continue Import button is independently clickable
  // with nothing stopping a user from starting a second row's resume while the first is still in
  // flight. A single `string | null` here would make the two fetches fight over one slot -- the
  // second click would silently clear the first row's spinner, and whichever fetch's `finally` ran
  // last would wipe out the other's, even while its own request was still pending. Caught by
  // adversarial review.
  const [resumingSessionIds, setResumingSessionIds] = useState<Set<string>>(new Set());
  // Which unfinished session's discard confirmation is showing, if any -- a custom in-app modal
  // (ConfirmDialog) instead of the browser's own confirm(), which rendered as unstyled OS chrome
  // (literally titled with the page's own origin) rather than looking like part of the product.
  const [confirmDiscardId, setConfirmDiscardId] = useState<string | null>(null);
  // Same idea, for the review screen's own "discard and start over" button rather than an entry
  // in the unfinished-imports list -- a plain boolean, since the review screen only ever has one
  // current session to discard.
  const [confirmDiscardReviewOpen, setConfirmDiscardReviewOpen] = useState(false);
  const { data: unfinishedSessions } = useQuery({
    queryKey: ['import-sessions'],
    queryFn: () => importApi.listSessions(),
  });

  useEffect(() => {
    // Non-critical background loads -- a failure here (e.g. a network blip, an expired token
    // mid-session) shouldn't crash this effect as an unhandled rejection; the page still works
    // with an empty category list / existing-account list, just with fewer detected defaults.
    categoriesApi.list().then((cats) => setCategories(cats.map((c) => c.name))).catch((e) => console.error('Failed to load categories', e));
    // Bug fix, caught by a review before ship: auto-resuming below used to fire
    // `resumeSession(resumeState.resumeSessionId)` directly in this effect, which calls
    // hydrateReviewFrom -> matchExistingAccount(..., existingAccounts) -- but this effect's
    // closure is fixed to this render's existingAccounts, `[]` at mount, and setState here never
    // updates an already-captured closure. The account match would silently run against an empty
    // list every time, always landing on "new account" even for a statement that plainly matches
    // one the user already has -- exactly the merge-risk bug the comment on matchExistingAccount's
    // caller (hydrateReviewFrom) exists to prevent, just reintroduced through a second entry
    // point. The button-driven "Continue previous import" call to resumeSession is unaffected --
    // its onClick closure is created fresh on every render, so by the time a user clicks it,
    // existingAccounts already reflects whatever this same accountsApi.list() call resolved to.
    // Fixed by passing the freshly-resolved list straight into resumeSession/hydrateReviewFrom
    // instead of letting them fall back to (stale) component state.
    accountsApi.list()
      .then((accounts) => {
        setExistingAccounts(accounts);
        // No `&& !reimportState` guard: reimportState/resumeState/retryState are mutually
        // exclusive by construction now (all three derive from the single navState.kind), so
        // resumeState truthy already implies the other two are null.
        if (resumeState) void resumeSession(resumeState.resumeSessionId, accounts);
      })
      .catch((e) => {
        console.error('Failed to load accounts', e);
        if (resumeState) void resumeSession(resumeState.resumeSessionId, []);
      });
    // Failing closed on purpose: if this call fails we simply use the synchronous path, which is
    // what every deployment supports. An import that works slowly beats one that does not start.
    importJobsApi.availability()
      .then((a) => setAsyncAvailable(a.asyncImportAvailable))
      .catch(() => setAsyncAvailable(false));

    if (reimportState) {
      setRows(reimportState.staging.rows);
      setReview(beginReview(reimportState.staging.rows));
      setChosenCategory(reimportState.staging.rows.map((r) => r.suggestedCategory));
      setDetectedAccount(reimportState.staging.detectedAccount);
      setVerification(reimportState.staging.verification ?? null);
      setUnparseableRows(reimportState.staging.unparseableRows);
      setAccountChoice('existing');
      setSelectedAccountId(reimportState.accountId);
      setStep('review');
    }
    // resumeState is handled above, chained after accountsApi.list() settles -- see the comment
    // there for why it can't run inline here the way the reimportState branch does.
    //
    // Only ever run once on mount — reimportState/resumeState come from router state at
    // navigation time, not something that changes while this page is open.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /**
   * File chosen (dropped, or picked from the file dialog). A CSV goes straight up, exactly as it
   * always has. A PDF stops here instead and waits on the password panel: most Indian banks
   * e-mail statements password-protected, so offering the field up front turns the common case
   * into one upload rather than an upload, a rejection, and a second upload.
   */
  function handleFile(file: File) {
    clearError();
    setMultiSections(null); // clear any previous multi-account run before staging a new file
    const lowerName = file.name.toLowerCase();
    const isPdf = lowerName.endsWith('.pdf');
    const isCsv = lowerName.endsWith('.csv');
    if (!isPdf && !isCsv) {
      showError('Please upload a .csv or .pdf bank/credit card statement.');
      return;
    }
    if (isPdf) {
      setPendingPdf(file);
      setPdfPassword('');
      setPasswordState(null);
      return;
    }
    void upload(file, false, undefined);
  }

  /**
   * Loads one staged statement into the review step.
   *
   * Shared because there are now three ways to arrive here — a synchronous upload, a queued job the
   * user watched, and a re-import — and each one setting eight pieces of state by hand is how the
   * confirm payload came to differ between two paths (see lib/newAccountPayload.ts). One of these
   * per arrival route is one too many.
   */
  function hydrateReviewFrom(staging: StagingResult, accountsForMatch: Account[] = existingAccounts) {
    setRows(staging.rows);
    // A flagged row starts EXCLUDED but UNRESOLVED -- not silently unticked. The confirm button
    // is blocked until every one has an explicit answer, so "I didn't mean to skip that" stops
    // being possible. Before WI5 the untick alone was the whole duplicate handling: the row
    // vanished from the import unless the user noticed a checkbox they had never touched.
    setReview(beginReview(staging.rows));
    setChosenCategory(staging.rows.map((r) => r.suggestedCategory));
    setDetectedAccount(staging.detectedAccount);
    setVerification(staging.verification ?? null);
    setUnparseableRows(staging.unparseableRows ?? []);

    // Pre-fill the new-account form from whatever the statement told us — every field here
    // is editable before anything is created, since detection is best-effort by design.
    setNewName(staging.detectedAccount.suggestedName);
    setNewType(staging.detectedAccount.suggestedAccountType);
    setNewOpeningBalance(staging.detectedAccount.openingBalance != null ? String(staging.detectedAccount.openingBalance) : '');
    setNewCreditLimit(staging.detectedAccount.creditLimit != null ? String(staging.detectedAccount.creditLimit) : '');
    setNewDueDate(staging.detectedAccount.paymentDueDate ?? '');

    // Default to the account this statement actually matches, and to "new account" when it
    // matches none.
    //
    // Bug fix: this used to preselect existingAccounts[0] whenever ANY account existed, while
    // the comment above it claimed to pick "the account the file's own signals most plausibly
    // matches". No signal was consulted. Whoever imported a Kotak statement first was then shown
    // "use an existing account: Kotak" for every later statement from any bank, and had to
    // notice and override it.
    //
    // Defaulting wrong in the two directions is not equally bad, which is why matchExistingAccount
    // returns null unless it is confident: preselecting the wrong EXISTING account merges one
    // institution's transactions into another's -- wrong balances, wrong net worth, reconciliation
    // running across two unrelated ledgers -- whereas preselecting NEW at worst creates a
    // duplicate account, which is visible and deletable.
    const match = matchExistingAccount(staging.detectedAccount, accountsForMatch);
    if (match) {
      setAccountChoice('existing');
      setSelectedAccountId(match.id);
    } else {
      setAccountChoice('new');
    }
  }

  /**
   * A queued import finished. Load what it staged and hand over to the same review step the
   * synchronous path uses — the whole point of the worker persisting a session.
   */
  async function openReviewedJob(sessionId: string) {
    try {
      const session = await importApi.getSession(sessionId);
      setSessionId(session.sessionId);
      hydrateReviewFrom(session.staging);
      setJobId(null);
      setStep('review');
    } catch (e: any) {
      setJobId(null);
      // Bug fix: the worker only stages, never confirms (ImportJobWorker's own doc comment says
      // so) -- but by the time this poller's COMPLETED tick fires and this fetch runs, the same
      // session can already have been confirmed through another path (a second tab resuming it,
      // a duplicate confirm). getSession then 400s with this code, same as resumeSession below
      // already handles -- mirrored here rather than shown as a generic, actively misleading
      // failure: nothing is unloaded (the import already succeeded), and "open it from your
      // unfinished imports" is a dead end since listResumableSessions never returns a confirmed
      // session.
      if (e.response?.data?.errorCode === IMPORT_SESSION_ALREADY_CONFIRMED) {
        showError('This import has already been reviewed and confirmed -- check your Statement History for it.');
        return;
      }
      showError('Your statement was imported, but the review could not be loaded. Open it from your unfinished imports.');
    }
  }

  /**
   * "Continue previous import" -- the same getSession -> hydrateReviewFrom -> step='review'
   * sequence openReviewedJob above already proves works, for a different arrival route: an
   * abandoned staged session rather than a completed queued job. No upload, no re-selecting a
   * file -- the bytes and staged rows are already server-side from the original upload.
   *
   * `accountsForMatch` is optional and only ever passed by the mount effect's auto-resume path
   * (Premium Import Reliability v1, §3.2) -- see its own comment for why that caller can't rely
   * on `hydrateReviewFrom`'s default fallback to component state. Every other caller (the button
   * below) omits it and gets that fallback, which is already correct for a click that happens
   * well after this page's account list has loaded.
   */
  async function resumeSession(id: string, accountsForMatch?: Account[]) {
    clearError();
    setResumingSessionIds((prev) => new Set(prev).add(id));
    try {
      const session = await importApi.getSession(id);
      setSessionId(session.sessionId);
      hydrateReviewFrom(session.staging, accountsForMatch);
      setStep('review');
    } catch (e: any) {
      // Bug fix, caught by review: isReviewable(job) on ImportDetail.tsx stays true forever once
      // a job completes with a session id, even after that session was already reviewed and
      // confirmed through the normal flow -- so "Review this import" can reach here for an import
      // that already succeeded. The old bare catch showed the same "may have expired, please
      // upload again" message for every failure, which is actively wrong in that case: nothing
      // needs re-uploading. Distinguished by ErrorCode, not by matching the message text, since
      // the message is free-text the backend owns and the UI shouldn't be branching on wording.
      if (e.response?.data?.errorCode === IMPORT_SESSION_ALREADY_CONFIRMED) {
        showError('This import has already been reviewed and confirmed -- check your Statement History for it.');
        return;
      }
      // The session most likely expired between the list loading and this click (the 48h window
      // can lapse mid-visit) -- refetch so the now-stale entry disappears rather than staying in
      // the list as a button that will fail again the same way.
      void queryClient.invalidateQueries({ queryKey: ['import-sessions'] });
      showError('This staged import is no longer available -- it may have expired. Please upload the statement again.');
    } finally {
      setResumingSessionIds((prev) => {
        const next = new Set(prev);
        next.delete(id);
        return next;
      });
    }
  }

  async function discardStagedSession(id: string): Promise<boolean> {
    // Bug fix, caught by review: this function never cleared the banner on success, unlike every
    // other action on this page -- an unrelated error left showing (e.g. an ACTION_REQUIRED parse
    // failure, amber-colored) would sit there indefinitely, now misleadingly still reading as
    // actionable guidance about a file no longer relevant to what the user just did.
    clearError();
    setDiscardingSessionId(id);
    try {
      await importApi.discardSession(id);
      await queryClient.invalidateQueries({ queryKey: ['import-sessions'] });
      return true;
    } catch {
      showError('Could not discard this staged import.');
      return false;
    } finally {
      setDiscardingSessionId(null);
    }
  }

  /** The review screen's own discard action -- distinct from discardStagedSession's other caller
   *  (the "Continue previous import" list) because only this one needs to leave the review screen
   *  afterward. Only calls startOver() when the discard actually succeeded -- if it failed,
   *  discardStagedSession already showed the error, and resetting the review state on top of an
   *  unresolved failure would silently discard what the user was looking at for no reason. */
  async function discardReviewSessionAndStartOver() {
    if (!sessionId) return;
    const discarded = await discardStagedSession(sessionId);
    if (discarded) startOver();
  }

  async function upload(file: File, isPdf: boolean, password: string | undefined) {
    clearError();
    setUploadProgress(0);
    try {
      // The queue, when this deployment has one and the file does not need a password.
      //
      // A protected PDF is deliberately excluded rather than made to work: the job carries a
      // content address and no password, and the worker opens the document minutes later with
      // nobody to ask. Sending it synchronously keeps the one flow where the person who knows the
      // password is still on the screen.
      if (asyncAvailable && !password) {
        const accepted = await importJobsApi.submit(file, setUploadProgress);
        setFileFormat(isPdf ? 'PDF' : 'CSV');
        setPendingPdf(null);
        setPdfPassword('');
        setPasswordState(null);
        setJobId(accepted.jobId);
        // StatementHistory's "Recent Imports" section (Premium Import Reliability v1, §3.2) reads
        // this same key with a 30s staleTime -- without this, a person who was on that page inside
        // the last 30s, came here to submit a statement, and went straight back would see the
        // pre-submission snapshot instead of the job they just started.
        void queryClient.invalidateQueries({ queryKey: ['import-jobs-recent'] });
        return;
      }

      const res = isPdf
        ? await importApi.stagePdf(file, setUploadProgress, password)
        : await importApi.stageCsv(file, setUploadProgress);
      setSessionId(res.sessionId);
      setFileFormat(isPdf ? 'PDF' : 'CSV');
      // The document opened, so the password (if any) has done its whole job. Drop both it and
      // the file: neither is needed again, and confirm/reimport work from the server-side session.
      setPendingPdf(null);
      setPdfPassword('');
      setPasswordState(null);

      // Multi-account PDF (e.g. an HSBC-style composite statement bundling a savings account and
      // a credit-card account in one file) -- res.staging is null here, res.sections is what's
      // populated instead. See PdfStagingSessionResult's own doc comment in endpoints.ts.
      if ('multiAccount' in res && res.multiAccount && res.sections) {
        setMultiSections(res.sections.map((s) => initialSectionState(s, existingAccounts)));
        setStep('review');
        return;
      }

      const staging = res.staging!; // guaranteed non-null whenever multiAccount is false/absent
      hydrateReviewFrom(staging);
      setStep('review');
    } catch (e: any) {
      // e.response is only ever populated when the server actually answered the request --
      // axios leaves it undefined for anything that never got a response at all (network down,
      // DNS failure, a timeout, or a CORS-blocked preflight). The browser deliberately doesn't
      // tell JS WHICH of those it was (a CORS block and a genuinely unreachable server look
      // identical to script code, for security reasons), but "we never even reached the server"
      // is still a meaningfully different failure than "the server looked at this file and
      // rejected it" -- conflating the two under "Could not parse this PDF" sent debugging
      // toward the parser every time this happened, when the parser was never actually involved.
      const code = e.response?.data?.errorCode;
      const contractMessage = importFailureMessage(code);
      if (!e.response) {
        showError('Unable to reach the import service. The upload request could not be completed — check your connection and try again.');
      } else if (code === PDF_PASSWORD_REQUIRED || code === PDF_PASSWORD_INVALID) {
        // Not a parse failure and not shown as one -- the file is fine, it just hasn't been
        // opened yet. The panel stays put with this same file so the retry is one field and one
        // click, and the message lives in the panel rather than in the page-level error banner.
        setPasswordState(code === PDF_PASSWORD_INVALID ? 'invalid' : 'required');
        setPendingPdf(file);
        // Focus after the panel has re-rendered with the new state, so the user can type straight
        // away instead of hunting for the field they were just asked to fill in.
        setTimeout(() => passwordInput.current?.focus(), 0);
      } else if (contractMessage) {
        // Premium Import Reliability v1 failure UX contract: for a code we have curated copy for,
        // that copy is what the user reads, not the server's `message` -- the whole point of the
        // contract is that Fynora controls the wording, even though the server's own message is
        // already reasonable prose (see ExtractionCheck.java). Only a code with no curated entry
        // falls through to the server message / generic fallback below. Sprint 4 item 22:
        // userActionRequired comes off the wire (ErrorCode.userActionRequired(), computed once
        // backend-side -- see GlobalExceptionHandler), not a second frontend-maintained copy of
        // which codes qualify, so the banner's color can never drift from the backend's own answer.
        showError(contractMessage, !!e.response?.data?.userActionRequired);
      } else {
        showError(e.response?.data?.message ?? (isPdf ? 'Could not parse this PDF.' : 'Could not parse this CSV.'));
      }
    } finally {
      setUploadProgress(null);
    }
  }

  // The single-account path's three review actions. Each is the same one-line delegation the
  // multi-account path makes per section (see the section cards below) -- the behaviour lives in
  // lib/importReview.ts so the two paths cannot drift, which is how they drifted in the first place.
  const decideDuplicate = (index: number, decision: DuplicateDecision) =>
    setReview((r) => decide(rows, r, index, decision));

  const applyDuplicateDecisionToSimilar = (index: number) =>
    setReview((r) => applyDecisionToSimilar(rows, r, index));

  const decideAllDuplicates = (decision: DuplicateDecision) =>
    setReview((r) => decideAllUnresolved(rows, r, decision));

  const toggleRowIncluded = (index: number, include: boolean) =>
    setReview((r) => setIncluded(r, index, include));

  // docs/proposals/account-ownership-intelligence-proposal.md §3.1 point 1: the extracted name is
  // untrusted input, not ground truth -- this is why a mismatch only ever opens a non-blocking
  // dialog here, never blocks confirmImport() outright.
  function ownershipNameMismatch(): boolean {
    const holder = detectedAccount?.accountHolderName;
    if (!holder) return false;
    // fullName is genuinely nullable (Apple Sign-In only supplies it on the first authorization --
    // see AuthContext's loginWithApple). Nothing on the profile side to compare against means
    // nothing to warn about, same "don't guess" principle OwnershipMatchService follows for this
    // exact case on the backend -- without this guard, a user with no profile name would see this
    // warning on every single import, and the dialog would show "null" as their profile name.
    if (!fullName) return false;
    return !isLikelyMatch(holder, fullName);
  }

  // ownershipAcknowledgedNow is an explicit override, not just a read of ownershipWarningAcknowledged
  // state -- the dialog's own "Continue Import" handler calls this function again immediately after
  // setting that state, and a React state update isn't visible in the same render's closure yet. The
  // override sidesteps that; the state still exists for the payload sent to the backend below.
  async function confirmImport(ownershipAcknowledgedNow = false) {
    if (!reimportState && !sessionId) return;
    const ownershipAcknowledged = ownershipWarningAcknowledged || ownershipAcknowledgedNow;
    if (!ownershipAcknowledged && ownershipNameMismatch()) {
      setOwnershipWarningOpen(true);
      return;
    }
    setConfirming(true);
    clearError();
    try {
      const rowPayload = toConfirmedRows(rows, review, chosenCategory);

      const existingAccountId = accountChoice === 'existing' ? selectedAccountId : null;
      // See lib/newAccountPayload.ts for why this is not an object literal here. The multi-account
      // path below builds its payload with the same function, which is the only thing that stops
      // the two drifting the way they already did once.
      const newAccount =
        accountChoice === 'new'
          ? toNewAccountPayload(
              { newName, newType, newOpeningBalance, newCreditLimit, newDueDate },
              detectedAccount,
            )
          : null;

      const result = reimportState
        ? await statementImportsApi.confirmReimport(reimportState.reimportId, {
            rows: rowPayload,
            existingAccountId,
            statementOpeningBalance: detectedAccount?.openingBalance ?? null,
            statementClosingBalance: detectedAccount?.closingBalance ?? null,
            statementPeriodStart: detectedAccount?.statementPeriodStart ?? null,
            statementPeriodEnd: detectedAccount?.statementPeriodEnd ?? null,
            totalAmountDue: detectedAccount?.totalAmountDue ?? null,
            paymentDueDate: detectedAccount?.paymentDueDate ?? null,
            password: reimportState.password,
            userConfirmedContinue: ownershipAcknowledged ? true : undefined,
          })
        : await importApi.confirm({
            sessionId: sessionId!,
            rows: rowPayload,
            existingAccountId,
            newAccount,
            statementOpeningBalance: detectedAccount?.openingBalance ?? null,
            statementClosingBalance: detectedAccount?.closingBalance ?? null,
            statementPeriodStart: detectedAccount?.statementPeriodStart ?? null,
            statementPeriodEnd: detectedAccount?.statementPeriodEnd ?? null,
            totalAmountDue: detectedAccount?.totalAmountDue ?? null,
            paymentDueDate: detectedAccount?.paymentDueDate ?? null,
            userConfirmedContinue: ownershipAcknowledged ? true : undefined,
          });
      setSummary(result);
      setStep('summary');
      invalidateImportRelatedQueries(queryClient);
    } catch (e: any) {
      showError(e.response?.data?.message ?? 'Could not complete the import.');
    } finally {
      setConfirming(false);
    }
  }

  // Confirms every section of a multi-account PDF staging session together -- the multi-account
  // counterpart to confirmImport() above. Builds one SectionConfirmPayload per detected account
  // (in the same order they were staged in) and posts them all in a single request; the backend
  // loop-calls the same per-account confirm logic confirmImport()'s single request goes through.
  async function confirmMultiImport() {
    if (!sessionId || !multiSections) return;
    setConfirming(true);
    clearError();
    try {
      const sections = multiSections.map((s) => {
        // The same builder the single-account confirm uses. This section used to hand-roll its own
        // row payload and omit confirmedNotDuplicate, so even with a review screen in front of it
        // the user's "import anyway" would have been honoured in the ledger and then reversed by
        // reconciliation -- the exact defect 55f2db0 fixed for the single-account path.
        const rowPayload = toConfirmedRows(s.rows, s.review, s.chosenCategory);
        const existingAccountId = s.accountChoice === 'existing' ? s.selectedAccountId : null;
        // The same builder the single-account confirm uses. This section used to hand-roll its own
        // and stopped at ifscCode, dropping detectedProduct, productIdentityHash and all seven
        // deposit attributes -- so a composite statement's fixed-deposit section was created as an
        // empty savings account, losing the principal and maturity the review screen had just shown
        // the user. SectionState satisfies NewAccountForm structurally, so it passes straight in.
        const newAccount =
          s.accountChoice === 'new' ? toNewAccountPayload(s, s.detectedAccount) : null;
        return {
          rows: rowPayload,
          existingAccountId,
          newAccount,
          statementOpeningBalance: s.detectedAccount.openingBalance ?? null,
          statementClosingBalance: s.detectedAccount.closingBalance ?? null,
          statementPeriodStart: s.detectedAccount.statementPeriodStart ?? null,
          statementPeriodEnd: s.detectedAccount.statementPeriodEnd ?? null,
          totalAmountDue: s.detectedAccount.totalAmountDue ?? null,
          paymentDueDate: s.detectedAccount.paymentDueDate ?? null,
        };
      });

      const result = await importApi.confirmMulti({ sessionId, sections });
      setMultiSummary(result.perAccount);
      setStep('summary');
      invalidateImportRelatedQueries(queryClient);
    } catch (e: any) {
      showError(e.response?.data?.message ?? 'Could not complete the import.');
    } finally {
      setConfirming(false);
    }
  }

  // Clears any one-time arrival context (the retry/reimport/resume banner and the state driving
  // it) so it can't resurface for whatever the person does next on this page -- location.state
  // otherwise persists unchanged across re-renders until a real navigate() replaces it, since
  // react-router never clears it on its own. `replace: true` clears it in place rather than
  // pushing a new history entry for what isn't really a navigation -- the person never left this
  // page.
  //
  // Bug fix, caught by review: this originally lived inline in startOver() only, covering
  // "finish an import, click Import Another" -- but two OTHER paths return this page to the same
  // plain "nothing pending" state (dismissing a failed job's timeline, giving up on a cancelled
  // one) and neither cleared it, so arriving via "Try again" for one file, then failing or
  // cancelling a second, unrelated upload, could still leave the FIRST file's stale "Retrying
  // <file>" banner showing. All three paths now share this one call.
  function clearArrivalState() {
    void navigate(location.pathname, { replace: true });
  }

  function startOver() {
    setStep('upload');
    setRows([]);
    setReview(EMPTY_REVIEW);
    setUnparseableRows([]);
    setSummary(null);
    setMultiSummary(null);
    setMultiSections(null);
    setUploadProgress(null);
    clearError();
    setFileFormat(null);
    setPendingPdf(null);
    setPdfPassword('');
    setPasswordState(null);
    setOwnershipWarningOpen(false);
    setOwnershipWarningAcknowledged(false);
    // Same reset the line above does for the ownership dialog, which this one was missing. Before
    // Phase 4b the omission was invisible -- the summary step early-returned above this dialog's
    // render, so a left-open flag could never resurface. Now that every step shares one tree, a
    // stale `true` would pop the discard dialog open again on the NEXT import's review step.
    setConfirmDiscardReviewOpen(false);
    accountsApi.list().then(setExistingAccounts).catch((e) => console.error('Failed to load accounts', e));
    clearArrivalState();
  }

  // Multi-account gate, derived rather than tracked -- one number over every section's own review,
  // computed by the same unresolvedCount the single-account confirm button uses.
  const outstandingMultiDuplicates = (multiSections ?? []).reduce(
    (n, s) => n + unresolvedCount(s.rows, s.review.decisions),
    0
  );
  const blockedSectionLabels = (multiSections ?? [])
    .map((s, i) => ({ s, i }))
    .filter(({ s }) => unresolvedCount(s.rows, s.review.decisions) > 0)
    .map(({ s, i }) => sectionLabel(s, i, multiSections?.length ?? 0));

  // Shared by the "continue previous import" list, the retry banner, and the dropzone itself below
  // -- hoisted once rather than repeated three times so the three conditions can't silently
  // diverge if one is edited later.
  const showUploadPicker = step === 'upload' && !jobId && !pendingPdf;

  return (
    <div className="space-y-4">
      {error && (
        // Sprint 4 item 22: warning (amber) for a code the user can fix themselves, matching the
        // password panel a few lines below and ImportTimeline's identical ACTION_REQUIRED/FAILED
        // split for the async path; danger (red) stays the default for everything else, unchanged.
        //
        // One banner above the step content, rather than one per step. Pre-Phase-4b this sat
        // between the two steps' blocks in source order, so its position was step-dependent: above
        // the transaction table on 'review', but below the dropzone on 'upload'. Consolidating to
        // a single instance means picking one position, and above wins -- a confirmImport() failure
        // buried under a long transaction table is the case that actually goes unnoticed. The
        // deliberate consequence is that an upload-step error now renders above the dropzone
        // instead of below it.
        <p className={`text-sm flex items-center gap-2 ${errorActionRequired ? 'text-warning' : 'text-danger'}`}>
          <AlertTriangle size={14} /> {error}
        </p>
      )}

      {/* Wizard-step transition (Phase 4b): 'upload' -> 'review' -> 'summary' fade/slide in place
          of the hard cut. mode="wait" lets the leaving step's exit finish before the next one
          enters, rather than cross-fading two full step screens on top of each other. Persistent
          chrome (error banner above, ConfirmDialogs below) stays OUTSIDE this block on purpose --
          it can be visible mid-step (e.g. a confirmImport() failure while still in 'review') and
          isn't part of the step being swapped. */}
      <AnimatePresence mode="wait">
        {/* `summary` takes precedence over `multiSummary` -- an `else`, not two independent `&&`
            blocks. AnimatePresence requires at most one child at a time for mode="wait" to work;
            the two used to be sequential early `return`s (structurally impossible for both to
            render), and collapsing them into a plain `else` keeps that same guarantee rather than
            leaning on setSummary/setMultiSummary never being out of sync with each other, which
            startOver() happens to maintain today but nothing enforces. */}
        {step === 'summary' && summary ? (
          <motion.div key="summary-single" {...stepMotionProps}>
            <ImportSummaryScreen summary={summary} onDone={() => navigate('/app')} onImportAnother={startOver} />
          </motion.div>
        ) : step === 'summary' && multiSummary ? (
          <motion.div key="summary-multi" {...stepMotionProps}>
            <MultiImportSummaryScreen summaries={multiSummary} onDone={() => navigate('/app')} onImportAnother={startOver} />
          </motion.div>
        ) : null}
        {step === 'upload' && (
          <motion.div key="upload" className="space-y-4" {...stepMotionProps}>
          {/* A queued import replaces the dropzone while it runs -- there is nothing useful to do on
              this page until it lands, and offering a second upload alongside it would start a race
              the user did not ask for. */}
          {jobId && (
            <>
              <ImportProgress
                jobId={jobId}
                onReady={(sessionId) => void openReviewedJob(sessionId)}
                onGaveUp={(job) => {
                  // Cancelling is the user's own decision and needs no explanation, so that path
                  // returns straight to the dropzone. A failure does NOT reset here -- ImportTimeline
                  // (below) is about to show the curated reason and the way back to the dropzone; an
                  // immediate reset would unmount it before anyone could read either.
                  if (job.status !== 'FAILED') {
                    setJobId(null);
                    setUploadProgress(null);
                    clearArrivalState();
                  }
                }}
              />
              <ImportTimeline
                jobId={jobId}
                onDismiss={() => {
                  setJobId(null);
                  setUploadProgress(null);
                  clearError();
                  clearArrivalState();
                }}
              />
            </>
          )}

          {!jobId && pendingPdf && (
            <form
              data-testid="pdf-password-panel"
              className="bg-card rounded p-6 shadow border border-border space-y-4"
              onSubmit={(e) => {
                e.preventDefault();
                if (uploadProgress === null) void upload(pendingPdf, true, pdfPassword || undefined);
              }}
            >
              <div className="flex items-center gap-2 text-sm text-ink">
                <FileText size={16} className="text-primary shrink-0" />
                <span className="font-medium break-all">{pendingPdf.name}</span>
              </div>

              <div>
                <label htmlFor="pdf-password" className="block text-sm font-medium text-ink mb-1">
                  Statement password <span className="font-normal text-muted">(optional)</span>
                </label>
                <input
                  id="pdf-password"
                  ref={passwordInput}
                  type="password"
                  // This is the bank's password for this document, not a Fynora credential -- offering
                  // to save it in a password manager would put it in the user's vault alongside real
                  // logins, and it changes with every statement anyway.
                  autoComplete="off"
                  className="w-full border border-border rounded px-3 py-2 text-sm"
                  placeholder="Leave blank if the file isn't protected"
                  value={pdfPassword}
                  onChange={(e) => setPdfPassword(e.target.value)}
                  disabled={uploadProgress !== null}
                  aria-describedby="pdf-password-help"
                />
                <p
                  id="pdf-password-help"
                  className={`text-xs mt-1 ${passwordState === 'invalid' ? 'text-danger' : 'text-muted'}`}
                  role={passwordState ? 'alert' : undefined}
                >
                  {passwordState === 'invalid'
                    ? "That password didn't open this statement — check it and try again."
                    : passwordState === 'required'
                      ? 'This statement is password protected. Enter the password your bank uses for it.'
                      : 'Many banks protect statements with a password — often a mix of your name, PAN, date of birth or account number. Check the email the statement came in.'}
                </p>
              </div>

              {uploadProgress !== null ? (
                <div data-testid="upload-progress">
                  <p className="font-medium text-sm text-ink mb-2">
                    {uploadProgress < 100 ? `Uploading… ${uploadProgress}%` : 'Processing statement…'}
                  </p>
                  <div className="w-full bg-border rounded-full h-2 overflow-hidden">
                    <div
                      className="bg-primary h-2 rounded-full transition-all duration-150"
                      style={{ width: `${uploadProgress}%` }}
                    />
                  </div>
                </div>
              ) : (
                <div className="flex items-center gap-3">
                  <Button type="submit">Upload statement</Button>
                  <button
                    type="button"
                    className="text-sm text-muted underline"
                    onClick={() => {
                      setPendingPdf(null);
                      setPdfPassword('');
                      setPasswordState(null);
                      clearError();
                    }}
                  >
                    Choose a different file
                  </button>
                </div>
              )}
            </form>
          )}

          {showUploadPicker && !!unfinishedSessions?.length && (
            <div className="bg-card rounded-xl2 shadow-card border border-border overflow-hidden">
              <div className="px-5 py-4 border-b border-border">
                <h2 className="font-semibold text-ink text-sm">Continue previous import</h2>
                <p className="text-xs text-muted">
                  You started importing these statements but didn't finish reviewing them.
                </p>
              </div>
              <div className="divide-y divide-border">
                {unfinishedSessions.map((s) => (
                  <div key={s.id} className="px-5 py-3.5 flex items-center justify-between gap-4 flex-wrap">
                    <div className="min-w-0 flex items-center gap-2.5">
                      <FileText size={16} className="text-muted flex-shrink-0" />
                      <div className="min-w-0">
                        <p className="text-sm font-medium text-ink truncate">{s.fileName}</p>
                        <p className="text-xs text-muted">
                          Uploaded {formatDate(s.createdAt)} · {s.rowCount} row{s.rowCount === 1 ? '' : 's'}
                        </p>
                      </div>
                    </div>
                    <div className="flex items-center gap-1.5 flex-shrink-0">
                      <Button
                        size="sm"
                        onClick={() => void resumeSession(s.id)}
                        disabled={discardingSessionId === s.id}
                        loading={resumingSessionIds.has(s.id)}
                      >
                        {!resumingSessionIds.has(s.id) && <RefreshCw size={12} />}
                        Continue Import
                      </Button>
                      <IconButton
                        variant="danger"
                        icon={<Trash2 size={14} />}
                        aria-label="Discard unfinished import"
                        title="Discard Unfinished Import"
                        onClick={() => setConfirmDiscardId(s.id)}
                        disabled={discardingSessionId === s.id}
                      />
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* Arrival from the Failed Imports section's "Try again" action (Premium Import
              Reliability v1, §2.5) -- purely informational, since there is no staged data to hydrate:
              the person still has to pick the file themselves, same as any fresh upload, so this
              exists only to remind them which file and why it failed last time. */}
          {showUploadPicker && retryState && (
            <div
              data-testid="retry-import-banner"
              className="bg-warning-bg border border-warning/30 rounded-xl2 px-5 py-3.5 flex items-start gap-2.5"
            >
              <AlertTriangle size={16} className="text-warning flex-shrink-0 mt-0.5" />
              <div>
                <p className="text-sm text-ink">
                  Retrying <span className="font-medium">{retryState.retryFileName}</span>
                </p>
                <p className="text-xs text-muted mt-0.5">
                  Last attempt: {importFailureMessage(retryState.retryFailureCode) ?? "Fynora couldn't complete this import."} Select the file below to try again.
                </p>
              </div>
            </div>
          )}

          {showUploadPicker && (
            <div
              data-testid="statement-dropzone"
              role="button"
              tabIndex={uploadProgress === null ? 0 : -1}
              aria-disabled={uploadProgress !== null}
              className={`bg-card rounded p-8 shadow border-2 border-dashed border-border text-center ${uploadProgress === null ? 'cursor-pointer' : 'cursor-default'}`}
              onClick={() => uploadProgress === null && fileInput.current?.click()}
              // Bug fix: the actual <input type="file"> is visually hidden (className="hidden",
              // display:none), which removes it from the tab order entirely -- a keyboard-only user
              // had no way to open the file picker on this page at all, the primary way data enters
              // Fynora. This div is now itself a focusable, keyboard-operable trigger (Enter/Space),
              // matching the standard accessible-clickable-div pattern.
              onKeyDown={(e) => {
                if (uploadProgress !== null) return;
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault();
                  fileInput.current?.click();
                }
              }}
              onDragOver={(e) => e.preventDefault()}
              onDrop={(e) => {
                e.preventDefault();
                if (uploadProgress === null && e.dataTransfer.files[0]) handleFile(e.dataTransfer.files[0]);
              }}
            >
              {uploadProgress !== null ? (
                <div data-testid="upload-progress">
                  <UploadCloud size={28} className="mx-auto mb-3 text-primary animate-pulse" />
                  <p className="font-medium text-sm text-ink mb-3">
                    {uploadProgress < 100 ? `Uploading… ${uploadProgress}%` : 'Processing statement…'}
                  </p>
                  <div className="w-full max-w-xs mx-auto bg-border rounded-full h-2 overflow-hidden">
                    <div
                      className="bg-primary h-2 rounded-full transition-all duration-150"
                      style={{ width: `${uploadProgress}%` }}
                    />
                  </div>
                </div>
              ) : (
                <>
                  <UploadCloud size={28} className="mx-auto mb-3 text-primary" />
                  <p className="font-medium text-sm text-ink">
                    <strong>Click to upload</strong> or drag a bank/credit card statement here
                  </p>
                  <p className="text-xs text-muted mt-2 flex items-center justify-center gap-3">
                    <span className="flex items-center gap-1"><FileSpreadsheet size={13} /> CSV exports</span>
                    <span className="flex items-center gap-1"><FileText size={13} /> PDF statements</span>
                  </p>
                  <p className="text-[11px] text-muted mt-2">
                    PDF support covers digital, text-based statements for now — a scanned or photographed
                    PDF won't have selectable text for us to read, so those still need a CSV export instead.
                  </p>
                </>
              )}
              <input
                ref={fileInput}
                type="file"
                accept=".csv,.pdf"
                data-testid="statement-file-input"
                className="hidden"
                disabled={uploadProgress !== null}
                onChange={(e) => {
                  const picked = e.target.files?.[0];
                  // Clearing the input matters now that a PDF can bounce back here via "Choose a
                  // different file": without it, re-picking the SAME file fires no change event and
                  // the page appears to ignore the click.
                  e.target.value = '';
                  if (picked) handleFile(picked);
                }}
              />
            </div>
          )}
          </motion.div>
        )}

        {step === 'review' && (
          <motion.div key="review" className="space-y-4" {...stepMotionProps}>
          {multiSections && (
            <>
              {/* Multi-account PDF (e.g. an HSBC-style composite statement bundling a savings
                  account and a credit-card account in one file) -- one card per detected section,
                  each independently reviewable, all confirmed together via one button below. */}
              <div className="bg-card rounded-xl2 shadow-card border border-border p-5">
                <h2 className="font-semibold text-ink text-sm mb-1">
                  This statement covers {multiSections.length} accounts
                </h2>
                <p className="text-xs text-muted flex items-center gap-2 flex-wrap">
                  <span>
                    We found {multiSections.length} separate accounts in this file — review each one below, then confirm
                    them all together.
                  </span>
                  {sessionId && (
                    <button
                      type="button"
                      onClick={() => setConfirmDiscardReviewOpen(true)}
                      // Disabled mid-confirm. This was harmless before Phase 4b, because reaching
                      // the summary step early-returned above the dialog's own render; collapsing
                      // those returns into one AnimatePresence tree means a dialog opened during an
                      // in-flight confirm now overlays the success screen instead -- and its
                      // "Discard" would fire discardSession() against an already-confirmed session.
                      disabled={confirming}
                      className="text-xs text-muted underline flex-shrink-0 disabled:opacity-50"
                    >
                      Not what you expected? Discard and start over
                    </button>
                  )}
                </p>
              </div>

              {multiSections.map((section, sectionIndex) => (
                /* data-testid scopes each card so a test (and Playwright's strict mode) can address one
                   section's duplicate review unambiguously -- N sections render N review panels, and
                   the panel keeps the same testids it has on the single-account path rather than
                   growing a per-section variant of its own. */
                <div
                  key={sectionIndex}
                  data-testid={`account-section-${sectionIndex}`}
                  className="bg-card rounded-xl2 shadow-card border border-border p-5 space-y-4"
                >
                  <h3 className="font-semibold text-ink text-sm">
                    {sectionLabel(section, sectionIndex, multiSections.length)}
                  </h3>

                  {/* This section's own report. Composite statements are exactly where a merged verdict
                      would mislead -- a savings section can verify while a credit-card section does not. */}
                  <VerificationPanel verification={section.verification} />

                  <AccountChoiceFields
                    existingAccounts={existingAccounts}
                    detectedAccount={section.detectedAccount}
                    accountChoice={section.accountChoice}
                    setAccountChoice={(v) => updateSection(setMultiSections, sectionIndex, { accountChoice: v })}
                    selectedAccountId={section.selectedAccountId}
                    setSelectedAccountId={(v) => updateSection(setMultiSections, sectionIndex, { selectedAccountId: v })}
                    newName={section.newName}
                    setNewName={(v) => updateSection(setMultiSections, sectionIndex, { newName: v })}
                    newType={section.newType}
                    setNewType={(v) => updateSection(setMultiSections, sectionIndex, { newType: v })}
                    newOpeningBalance={section.newOpeningBalance}
                    setNewOpeningBalance={(v) => updateSection(setMultiSections, sectionIndex, { newOpeningBalance: v })}
                    newCreditLimit={section.newCreditLimit}
                    setNewCreditLimit={(v) => updateSection(setMultiSections, sectionIndex, { newCreditLimit: v })}
                    newDueDate={section.newDueDate}
                    setNewDueDate={(v) => updateSection(setMultiSections, sectionIndex, { newDueDate: v })}
                  />

                  <TransactionPreviewTable
                    rows={section.rows}
                    review={section.review}
                    onToggleIncluded={(rowIndex, include) =>
                      updateSection(setMultiSections, sectionIndex, (s) => ({ review: setIncluded(s.review, rowIndex, include) }))
                    }
                    chosenCategory={section.chosenCategory}
                    setChosenCategory={(updater) => updateSection(setMultiSections, sectionIndex, { chosenCategory: updater(section.chosenCategory) })}
                    categories={categories}
                  />

                  <UnparseableRowsPanel rows={section.unparseableRows} />

                  {/* The same review the single-account path gets, once per detected account. Rendered
                      per section rather than merged into one list because a decision is about a row in
                      a specific account's ledger -- and because two sections can flag the same
                      description against different existing transactions, which one merged list would
                      present as one question. */}
                  <DuplicateReview
                    rows={section.rows}
                    decisions={section.review.decisions}
                    onDecide={(rowIndex, decision) =>
                      updateSection(setMultiSections, sectionIndex, (s) => ({ review: decide(s.rows, s.review, rowIndex, decision) }))
                    }
                    onApplyToSimilar={(rowIndex) =>
                      updateSection(setMultiSections, sectionIndex, (s) => ({ review: applyDecisionToSimilar(s.rows, s.review, rowIndex) }))
                    }
                    onDecideAll={(decision) =>
                      updateSection(setMultiSections, sectionIndex, (s) => ({ review: decideAllUnresolved(s.rows, s.review, decision) }))
                    }
                  />
                </div>
              ))}

              <div className="bg-card rounded shadow p-4">
                {/* The gate is one button over N sections, so it has to say WHICH account is still
                    blocking -- a disabled button with the reason three screens up is a dead end. */}
                {outstandingMultiDuplicates > 0 && (
                  <p data-testid="multi-duplicate-gate" role="status" className="text-xs text-danger mb-3">
                    {outstandingMultiDuplicates} possible duplicate{outstandingMultiDuplicates === 1 ? '' : 's'} still{' '}
                    {outstandingMultiDuplicates === 1 ? 'needs' : 'need'} a decision, in{' '}
                    {blockedSectionLabels.join(' and ')}. Nothing is imported or skipped until you decide.
                  </p>
                )}
                <Button
                  onClick={confirmMultiImport}
                  loading={confirming}
                  disabled={
                    multiSections.some((s) => s.accountChoice === 'existing' && !s.selectedAccountId) ||
                    // The same gate the single-account path applies, summed across every section. A
                    // statement covering two accounts is not two imports the user can partially
                    // approve -- confirmMulti posts them together, so one unanswered row anywhere
                    // blocks all of it, exactly as one unanswered row blocks a single-account import.
                    outstandingMultiDuplicates > 0
                  }
                >
                  Confirm All {multiSections.length} Accounts
                </Button>
              </div>
            </>
          )}

          {!multiSections && (
            <>
              {/* Above the account card on purpose: whether the numbers can be trusted is worth
                  reading before deciding where to put them. */}
              <VerificationPanel verification={verification} />

              {reimportState ? (
                <div className="bg-card rounded-xl2 shadow-card border border-border p-5">
                  <h2 className="font-semibold text-ink text-sm mb-1">Re-importing statement</h2>
                  <p className="text-xs text-muted">
                    Into <span className="text-ink font-medium">{reimportState.accountName}</span> — the account this
                    statement was originally imported into. Duplicate detection below runs against everything already on
                    the books, including this statement's own prior transactions.
                  </p>
                </div>
              ) : (
              /* Account: existing vs. auto-created new one */
              <div className="bg-card rounded-xl2 shadow-card border border-border p-5">
                <h2 className="font-semibold text-ink text-sm mb-1">Which account is this statement for?</h2>
                <p className="text-xs text-muted mb-4">
                  Fields below were detected from the file where possible — review and edit before confirming.
                </p>

                <div className="flex gap-4 mb-4">
                  <label className="flex items-center gap-2 text-sm text-ink">
                    <input
                      type="radio"
                      checked={accountChoice === 'existing'}
                      onChange={() => setAccountChoice('existing')}
                      disabled={existingAccounts.length === 0}
                    />
                    Use an existing account
                  </label>
                  <label className="flex items-center gap-2 text-sm text-ink">
                    <input type="radio" checked={accountChoice === 'new'} onChange={() => setAccountChoice('new')} />
                    Create a new account from this statement
                  </label>
                </div>

                <AccountChoiceFields
                  existingAccounts={existingAccounts}
                  detectedAccount={detectedAccount}
                  accountChoice={accountChoice}
                  setAccountChoice={setAccountChoice}
                  selectedAccountId={selectedAccountId}
                  setSelectedAccountId={setSelectedAccountId}
                  newName={newName}
                  setNewName={setNewName}
                  newType={newType}
                  setNewType={setNewType}
                  newOpeningBalance={newOpeningBalance}
                  setNewOpeningBalance={setNewOpeningBalance}
                  newCreditLimit={newCreditLimit}
                  setNewCreditLimit={setNewCreditLimit}
                  newDueDate={newDueDate}
                  setNewDueDate={setNewDueDate}
                  hideChoiceRadio
                />
              </div>
              )}

              {/* Transaction preview */}
              <div className="bg-card rounded shadow p-4 overflow-x-auto">
                <p className="text-sm mb-3 text-ink flex items-center gap-2 flex-wrap">
                  <span>
                    {rows.length} row(s) parsed and auto-categorized. Low-confidence guesses (marked below) will still
                    import, but land on your Dashboard's review card afterward instead of being learned silently.
                  </span>
                  {fileFormat && (
                    <span className="text-[10px] uppercase font-semibold text-muted border border-border rounded px-1.5 py-0.5 flex items-center gap-1 flex-shrink-0">
                      {fileFormat === 'PDF' ? <FileText size={11} /> : <FileSpreadsheet size={11} />} {fileFormat}
                    </span>
                  )}
                  {sessionId && !reimportState && (
                    <button
                      type="button"
                      onClick={() => setConfirmDiscardReviewOpen(true)}
                      // Disabled mid-confirm. This was harmless before Phase 4b, because reaching
                      // the summary step early-returned above the dialog's own render; collapsing
                      // those returns into one AnimatePresence tree means a dialog opened during an
                      // in-flight confirm now overlays the success screen instead -- and its
                      // "Discard" would fire discardSession() against an already-confirmed session.
                      disabled={confirming}
                      className="text-xs text-muted underline flex-shrink-0 disabled:opacity-50"
                    >
                      Not what you expected? Discard and start over
                    </button>
                  )}
                </p>
                <TransactionPreviewTable
                  rows={rows}
                  review={review}
                  onToggleIncluded={toggleRowIncluded}
                  chosenCategory={chosenCategory}
                  setChosenCategory={setChosenCategory}
                  categories={categories}
                />

                <UnparseableRowsPanel rows={unparseableRows} />

                <DuplicateReview
                  rows={rows}
                  decisions={review.decisions}
                  onDecide={decideDuplicate}
                  onApplyToSimilar={applyDuplicateDecisionToSimilar}
                  onDecideAll={decideAllDuplicates}
                />

                <Button
                  onClick={() => void confirmImport()}
                  loading={confirming}
                  className="mt-4"
                  disabled={
                    (!reimportState && !sessionId) ||
                    (!reimportState && accountChoice === 'existing' && !selectedAccountId) ||
                    // The gate. Every flagged row must have an explicit answer before anything is
                    // written to the ledger -- which is what stops a duplicate being resolved by
                    // inattention rather than by a decision.
                    unresolvedCount(rows, review.decisions) > 0
                  }
                >
                  Confirm Import
                </Button>
              </div>
            </>
          )}
          </motion.div>
        )}
      </AnimatePresence>

      {/* Each dialog is gated to the step that owns it, restoring by construction what the two early
          `return <...SummaryScreen/>` statements used to guarantee for free: a discard dialog can
          never render over the summary screen, where answering it would fire discardSession()
          against a session the backend has already finalized.

          `disabled={confirming}` on the trigger links closes only ONE of the two orderings -- the
          user opening the dialog AFTER a confirm is already in flight. It does nothing about the
          reverse (dialog already open when the confirm starts), which ConfirmDialog's own lack of a
          focus trap leaves reachable by keyboard: its backdrop swallows mouse clicks, but Tab still
          walks the controls behind it. Gating the render is what actually closes both. */}
      {step === 'upload' && confirmDiscardId && (
        <ConfirmDialog
          title="Discard this unfinished import?"
          message="You can upload the statement again later."
          confirmLabel="Discard"
          danger
          onConfirm={() => {
            const id = confirmDiscardId;
            setConfirmDiscardId(null);
            void discardStagedSession(id);
          }}
          onCancel={() => setConfirmDiscardId(null)}
        />
      )}

      {step === 'review' && confirmDiscardReviewOpen && (
        <ConfirmDialog
          title="Discard this import and start over?"
          message="This clears everything parsed from this file. You can upload the statement again right after."
          confirmLabel="Discard"
          danger
          onConfirm={() => {
            setConfirmDiscardReviewOpen(false);
            void discardReviewSessionAndStartOver();
          }}
          onCancel={() => setConfirmDiscardReviewOpen(false)}
        />
      )}

      {step === 'review' && ownershipWarningOpen && (
        <ConfirmDialog
          title="Statement Check"
          message={`The statement holder name ("${detectedAccount?.accountHolderName}") differs from your Finora profile name ("${fullName}"). Please confirm you've selected the correct statement before continuing.`}
          confirmLabel="Continue Import"
          cancelLabel="Upload Different Statement"
          onConfirm={() => {
            setOwnershipWarningOpen(false);
            setOwnershipWarningAcknowledged(true);
            void confirmImport(true);
          }}
          onCancel={() => {
            setOwnershipWarningOpen(false);
            startOver();
          }}
        />
      )}
    </div>
  );
}

// Small helper for updating one section's state within the multiSections array by index --
// used throughout the multi-account review UI above instead of hand-rolling the same
// map-and-replace pattern at every call site.
//
// The patch may be a function of the section's PREVIOUS state, which is what the review actions
// use: a decision computed from the render closure's copy would be lost whenever two updates land
// in the same React batch (clicking "Import anyway" and then immediately "Apply to N similar" is
// exactly that), and losing a duplicate decision silently is the failure this whole item is about.
function updateSection(
  setMultiSections: Dispatch<SetStateAction<SectionState[] | null>>,
  index: number,
  patch: Partial<SectionState> | ((section: SectionState) => Partial<SectionState>),
) {
  setMultiSections((prev) =>
    prev
      ? prev.map((s, i) => (i === index ? { ...s, ...(typeof patch === 'function' ? patch(s) : patch) } : s))
      : prev
  );
}

/** How one detected account section is named, in its own heading and anywhere else that has to
 *  point at it (the multi-account duplicate gate below the confirm button). One function so the
 *  gate can never name a section differently from the card the user has to scroll to. */
function sectionLabel(section: SectionState, index: number, total: number): string {
  const bank = section.detectedAccount.bank;
  const suffix = bank.id !== 'OTHER' && bank.officialName ? ` — ${bank.officialName}` : '';
  return `Account ${index + 1} of ${total}${suffix}`;
}

// The existing-vs-new account picker + new-account detail fields -- shared between the
// single-account review step and each account card in the multi-account review step, so the two
// stay pixel-identical instead of drifting apart as separate copies.
/**
 * "1 Savings Account, 1 Fixed Deposit" rather than a bare list of account names.
 *
 * A combined statement used to report "3 accounts created", which was both less informative and
 * wrong -- two of those three were deposits, which are not accounts. Falls back to the account
 * names when the server hasn't sent product counts (an older response), so the summary never goes
 * blank on a shape it doesn't recognise.
 */
function formatProductsCreated(summary: { accountsCreated: string[]; productsCreated?: Record<string, number> }): string {
  const counts = Object.entries(summary.productsCreated ?? {});
  if (counts.length === 0) return summary.accountsCreated.join(', ');
  return counts.map(([product, n]) => `${n} ${productLabel(product)}${n > 1 ? 's' : ''}`).join(', ');
}

/** Human labels for FinancialProductType. Kept here rather than derived by replacing underscores
 *  so "PPF" and "Fixed Deposit" both read correctly. */
const PRODUCT_LABELS: Record<string, string> = {
  SAVINGS: 'Savings Account', CURRENT: 'Current Account', OVERDRAFT: 'Overdraft',
  WALLET: 'Wallet', CREDIT_CARD: 'Credit Card',
  FIXED_DEPOSIT: 'Fixed Deposit', RECURRING_DEPOSIT: 'Recurring Deposit',
  PPF: 'PPF', EPF: 'EPF', NPS: 'NPS', MUTUAL_FUND: 'Mutual Fund', DEMAT: 'Demat',
  LOAN: 'Loan', INSURANCE: 'Insurance', FOREX_CARD: 'Forex Card', UNKNOWN: 'Unidentified product',
};

function productLabel(product: string): string {
  return PRODUCT_LABELS[product] ?? product.replace(/_/g, ' ');
}

/**
 * What the engine thinks this section is, and how sure it is.
 *
 * Two deliberately different messages. A confident, validated product is a one-line confirmation.
 * Anything the engine could not identify or could not prove asks the user instead of quietly
 * prefilling a guess -- because a wrong product writes wrong data into their net worth silently,
 * while asking costs one dropdown. The evidence is shown on demand so the answer can be argued
 * with rather than taken on trust.
 */
function ProductDetectionNotice({ detected }: { detected: DetectedAccountInfo }) {
  const [showEvidence, setShowEvidence] = useState(false);
  const confidence = Math.round((detected.productConfidence ?? 0) * 100);

  if (!detected.productNeedsReview && detected.detectedProduct !== 'UNKNOWN') {
    return (
      <div className="md:col-span-2 flex items-center gap-2 text-xs text-muted">
        <span aria-hidden="true">✓</span>
        <p>
          Detected a <span className="font-semibold text-ink">{productLabel(detected.detectedProduct)}</span>
          {confidence > 0 && <span> ({confidence}% confidence)</span>}.
        </p>
      </div>
    );
  }

  return (
    <div className="md:col-span-2 bg-amber-50 dark:bg-amber-500/10 border border-amber-300/60 rounded-lg px-3 py-2.5">
      <p className="text-xs text-ink">
        {detected.detectedProduct === 'UNKNOWN'
          ? 'We found a financial product in this statement but couldn’t identify what kind it is.'
          : `This looks like a ${productLabel(detected.detectedProduct)}, but we couldn’t confirm it from the statement.`}
        {' '}Please pick the right type below — we’ll remember it.
      </p>
      {detected.productEvidence?.length > 0 && (
        <>
          <button
            type="button"
            onClick={() => setShowEvidence((v) => !v)}
            aria-expanded={showEvidence}
            className="mt-1.5 text-xs text-primary underline underline-offset-2"
          >
            {showEvidence ? 'Hide' : 'Why?'}
          </button>
          {showEvidence && (
            <ul className="mt-1.5 space-y-0.5 text-[11px] text-muted list-disc list-inside">
              {detected.productEvidence.map((line, i) => <li key={i}>{line}</li>)}
            </ul>
          )}
        </>
      )}
    </div>
  );
}

function AccountChoiceFields({
  existingAccounts,
  detectedAccount,
  accountChoice,
  setAccountChoice,
  selectedAccountId,
  setSelectedAccountId,
  newName,
  setNewName,
  newType,
  setNewType,
  newOpeningBalance,
  setNewOpeningBalance,
  newCreditLimit,
  setNewCreditLimit,
  newDueDate,
  setNewDueDate,
  hideChoiceRadio,
}: {
  existingAccounts: Account[];
  detectedAccount: DetectedAccountInfo | null;
  accountChoice: AccountChoice;
  setAccountChoice: (v: AccountChoice) => void;
  selectedAccountId: string;
  setSelectedAccountId: (v: string) => void;
  newName: string;
  setNewName: (v: string) => void;
  newType: Account['accountType'];
  setNewType: (v: Account['accountType']) => void;
  newOpeningBalance: string;
  setNewOpeningBalance: (v: string) => void;
  newCreditLimit: string;
  setNewCreditLimit: (v: string) => void;
  newDueDate: string;
  setNewDueDate: (v: string) => void;
  // The single-account review step renders its own choice-radio pair above this component (kept
  // there rather than duplicated inside, since it sits alongside a heading this component doesn't
  // own) -- the multi-account case renders it here instead, once per account card.
  hideChoiceRadio?: boolean;
}) {
  return (
    <>
      {!hideChoiceRadio && (
        <div className="flex gap-4 mb-4">
          <label className="flex items-center gap-2 text-sm text-ink">
            <input
              type="radio"
              checked={accountChoice === 'existing'}
              onChange={() => setAccountChoice('existing')}
              disabled={existingAccounts.length === 0}
            />
            Use an existing account
          </label>
          <label className="flex items-center gap-2 text-sm text-ink">
            <input type="radio" checked={accountChoice === 'new'} onChange={() => setAccountChoice('new')} />
            Create a new account from this statement
          </label>
        </div>
      )}

      {accountChoice === 'existing' ? (
        <select
          aria-label="Select an existing account"
          value={selectedAccountId}
          onChange={(e) => setSelectedAccountId(e.target.value)}
          className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm w-full max-w-sm"
        >
          {existingAccounts.length === 0 && <option value="">No accounts yet</option>}
          {existingAccounts.map((a) => (
            <option key={a.id} value={a.id}>
              {a.name} ({a.accountType.replace('_', ' ')})
            </option>
          ))}
        </select>
      ) : (
        <div className="grid md:grid-cols-2 gap-3">
          {detectedAccount && <ProductDetectionNotice detected={detectedAccount} />}
          {detectedAccount && detectedAccount.bank.id !== 'OTHER' && (
            <div className="md:col-span-2 flex items-center gap-2.5 bg-primary-light border border-primary/20 rounded-lg px-3 py-2">
              <BankLogo bank={detectedAccount.bank} size={28} />
              <p className="text-xs text-ink">
                Detected <span className="font-semibold">{detectedAccount.bank.officialName}</span> from this statement.
              </p>
            </div>
          )}
          <div>
            <label htmlFor="import-account-name" className="block text-xs uppercase text-muted mb-1">Account name</label>
            <input id="import-account-name" value={newName} onChange={(e) => setNewName(e.target.value)} className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm w-full" />
          </div>
          <div>
            <label htmlFor="import-account-type" className="block text-xs uppercase text-muted mb-1">Account type</label>
            <select id="import-account-type" value={newType} onChange={(e) => setNewType(e.target.value as Account['accountType'])} className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm w-full">
              <option value="SAVINGS">Savings</option>
              <option value="CREDIT_CARD">Credit Card</option>
              <option value="WALLET">Wallet</option>
              <option value="INVESTMENT">Investment</option>
            </select>
          </div>
          <div>
            <label htmlFor="import-opening-balance" className="block text-xs uppercase text-muted mb-1">
              Opening balance {detectedAccount?.openingBalance != null && <span className="normal-case text-primary">(detected)</span>}
            </label>
            <input id="import-opening-balance" type="number" value={newOpeningBalance} onChange={(e) => setNewOpeningBalance(e.target.value)} className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm w-full" />
          </div>
          {detectedAccount?.accountHolderName && (
            <div>
              <label htmlFor="import-account-holder" className="block text-xs uppercase text-muted mb-1">Account holder (detected)</label>
              <input id="import-account-holder" value={detectedAccount.accountHolderName} disabled className="border border-border rounded-lg px-3 py-2 text-sm w-full bg-bg text-muted" />
            </div>
          )}
          {detectedAccount?.accountNumberMasked && (
            <div>
              <span className="block text-xs uppercase text-muted mb-1">Account number (detected)</span>
              {/* Hidden by default with an eye to reveal, matching how the Accounts page shows the
                  same field. This used to render the number outright in a disabled input -- the one
                  place in the app that did, and the one screen most likely to be shared while
                  someone walks through an import. What "reveal" shows is the bank's own masked form
                  (e.g. "XXXXXX4587"); a full number does not exist to show. */}
              <div className="border border-border rounded-lg px-3 py-2 text-sm w-full bg-bg text-muted">
                <MaskedAccountNumber value={detectedAccount.accountNumberMasked} />
              </div>
            </div>
          )}
          {detectedAccount?.branchName && (
            <div>
              <label htmlFor="import-branch" className="block text-xs uppercase text-muted mb-1">Branch (detected)</label>
              <input id="import-branch" value={detectedAccount.branchName} disabled className="border border-border rounded-lg px-3 py-2 text-sm w-full bg-bg text-muted" />
            </div>
          )}
          {detectedAccount?.ifscCode && (
            <div>
              <label htmlFor="import-ifsc" className="block text-xs uppercase text-muted mb-1">IFSC code (detected)</label>
              <input id="import-ifsc" value={detectedAccount.ifscCode} disabled className="border border-border rounded-lg px-3 py-2 text-sm w-full bg-bg text-muted" />
            </div>
          )}
          {newType === 'CREDIT_CARD' && (
            <>
              <div>
                <label htmlFor="import-credit-limit" className="block text-xs uppercase text-muted mb-1">Credit limit</label>
                <input id="import-credit-limit" type="number" value={newCreditLimit} onChange={(e) => setNewCreditLimit(e.target.value)} className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm w-full" />
              </div>
              <div>
                <label htmlFor="import-due-date" className="block text-xs uppercase text-muted mb-1">Payment due date</label>
                <input id="import-due-date" type="date" value={newDueDate} onChange={(e) => setNewDueDate(e.target.value)} className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm w-full" />
              </div>
              {detectedAccount?.totalAmountDue != null && (
                <div>
                  <span className="block text-xs uppercase text-muted mb-1">Total amount due (detected)</span>
                  <div className="border border-border rounded-lg px-3 py-2 text-sm w-full bg-bg text-muted">
                    {fmt(detectedAccount.totalAmountDue)}
                  </div>
                </div>
              )}
            </>
          )}
          {(detectedAccount?.statementPeriodStart || detectedAccount?.closingBalance != null) && (
            <div className="md:col-span-2 text-xs text-muted">
              {detectedAccount?.statementPeriodStart && (
                <span>Statement period: {detectedAccount.statementPeriodStart} to {detectedAccount.statementPeriodEnd}. </span>
              )}
              {detectedAccount?.closingBalance != null && <span>Closing balance on statement: {fmt(detectedAccount.closingBalance)}.</span>}
            </div>
          )}
        </div>
      )}
    </>
  );
}

// The staged-row table (include checkbox, date/description/amount, category picker) -- shared
// between the single-account review step and each account card in the multi-account review step.
function TransactionPreviewTable({
  rows,
  review,
  onToggleIncluded,
  chosenCategory,
  setChosenCategory,
  categories,
}: {
  rows: StagedRow[];
  // The whole review rather than a bare boolean[]: the include flags and the decisions that gate
  // them are one value by construction (see lib/importReview.ts), and taking them apart here is
  // how the multi-account path ended up able to untick a row with no decision attached to it.
  review: RowReview;
  onToggleIncluded: (index: number, include: boolean) => void;
  chosenCategory: string[];
  setChosenCategory: (updater: (arr: string[]) => string[]) => void;
  categories: string[];
}) {
  return (
    <table className="w-full text-xs font-mono mb-4">
      <thead>
        <tr className="text-left text-[10px] uppercase text-gray-500">
          <th className="p-1"></th><th className="p-1">Date</th><th className="p-1">Description</th>
          <th className="p-1 text-right">DR</th><th className="p-1 text-right">CR</th><th className="p-1">Category</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((r, i) => (
          <tr key={i} className="border-b border-dashed">
            <td className="p-1">
              <input
                type="checkbox"
                aria-label={`Include ${r.description}`}
                checked={review.included[i]}
                onChange={(e) => onToggleIncluded(i, e.target.checked)}
              />
            </td>
            <td className="p-1">{formatDateDDMMMYYYY(r.date)}</td>
            <td className="p-1">
              {r.description}
              {r.merchant && (
                <div className="text-[10px] text-muted">Detected: {r.merchant}</div>
              )}
              {r.likelyDuplicate && <span className="text-danger text-[10px] uppercase ml-1">duplicate</span>}
              {isUnconfirmedGuess(r.categorySource) && (
                <span className="text-[10px] uppercase ml-1" style={{ color: '#d97706' }}>low confidence</span>
              )}
            </td>
            {/* r.type is the backend's own authoritative direction signal (StagedRow.type,
                'INCOME' | 'EXPENSE') -- amount itself is always the absolute value, never signed,
                so direction must come from type, never inferred from the number's sign. */}
            <td className="p-1 text-right">{r.type === 'EXPENSE' ? `₹${r.amount}` : '—'}</td>
            <td className="p-1 text-right">{r.type === 'INCOME' ? `₹${r.amount}` : '—'}</td>
            <td className="p-1">
              <select
                value={chosenCategory[i]}
                onChange={(e) => setChosenCategory((arr) => arr.map((v, j) => (j === i ? e.target.value : v)))}
                className="bg-card text-ink border border-border rounded px-1 py-0.5 text-xs"
              >
                {categories.map((c) => (
                  <option key={c} value={c}>{c}</option>
                ))}
              </select>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

// "Never lose information" (see the engineering principles doc): rows the backend saw but could
// not parse into a transaction. Never confirmable -- purely so the user can see what was skipped
// instead of a row silently vanishing from the count.
//
// Deliberately just a count, not the raw per-row reason/field dump this used to show (e.g. "Date
// value 'DISCLAIMER' didn't match any known date format" plus the raw extracted cell text) --
// that level of parser-internals detail is diagnostic information for support/engineering to
// debug an extraction gap, not something a person importing their own bank statement needs to
// see or can act on. The raw detail isn't persisted anywhere yet (see Sprint tracking for the
// admin-portal viewer this could feed later); today it's simply not surfaced to the end user.
function UnparseableRowsPanel({ rows }: { rows: UnparseableRow[] }) {
  if (rows.length === 0) return null;

  return (
    <div className="bg-warning-bg border border-warning rounded-lg p-3 mb-4 text-xs font-semibold text-ink flex items-center gap-2">
      <AlertTriangle size={13} className="text-warning flex-shrink-0" />
      {rows.length} row{rows.length === 1 ? '' : 's'} couldn't be matched to a transaction (e.g. statement disclaimers or balance summaries) and won't be imported.
    </div>
  );
}

// A statement's warnings (Phase 2's gap/duplicate-period notices) plus, when this confirm's own
// period exactly duplicated an existing statement (summary.duplicateOfStatementId), the Phase 4
// (§0.3/§0.23) "Import this one as a replacement?" action that supersedes it. Shared between
// ImportSummaryScreen and each per-account card in MultiImportSummaryScreen -- each `summary` is
// its own independent ImportSummary (one per confirmed section), so one instance of this per
// summary, with its own local state, is exactly right: nothing here needs to be shared or merged
// across accounts.
function StatementWarnings({ summary }: { summary: ImportSummary }) {
  const [confirmingSupersede, setConfirmingSupersede] = useState(false);
  const [supersedeStatus, setSupersedeStatus] = useState<'idle' | 'loading' | 'error'>('idle');
  const [supersedeResult, setSupersedeResult] = useState<SupersedeResult | null>(null);
  const [supersedeError, setSupersedeError] = useState<string | null>(null);

  async function confirmSupersede() {
    if (!summary.duplicateOfStatementId) return;
    setConfirmingSupersede(false);
    setSupersedeStatus('loading');
    setSupersedeError(null);
    try {
      const result = await statementImportsApi.supersede(summary.duplicateOfStatementId, summary.statementImportId);
      setSupersedeResult(result);
      setSupersedeStatus('idle');
    } catch (e: any) {
      setSupersedeStatus('error');
      setSupersedeError(e.response?.data?.message ?? 'Could not replace the existing statement.');
    }
  }

  if (summary.warnings.length === 0) return null;

  return (
    <>
      <div className="bg-warning-bg border border-warning rounded-lg p-3 mb-4">
        {summary.warnings.map((w, i) => (
          <p key={i} className="text-xs text-ink flex items-start gap-2">
            <AlertTriangle size={13} className="text-warning flex-shrink-0 mt-0.5" /> {w}
          </p>
        ))}
        {summary.duplicateOfStatementId && !supersedeResult && (
          <button
            onClick={() => setConfirmingSupersede(true)}
            disabled={supersedeStatus === 'loading'}
            className="mt-2 text-xs font-semibold text-warning underline disabled:opacity-50"
          >
            {supersedeStatus === 'loading' ? 'Replacing…' : 'Import this one as a replacement?'}
          </button>
        )}
        {supersedeError && (
          <p className="text-xs text-danger mt-2">{supersedeError}</p>
        )}
        {supersedeResult && (
          <p className="text-xs text-ink mt-2 flex items-start gap-2">
            <CheckCircle2 size={13} className="text-success flex-shrink-0 mt-0.5" />
            <span>
              The existing statement has been replaced.
              {supersedeResult.warning && ` ${supersedeResult.warning}`}
            </span>
          </p>
        )}
      </div>

      {confirmingSupersede && (
        <ConfirmDialog
          title="Import this one as a replacement?"
          message="The existing statement for this period will stop counting toward your balance, coverage, and insights. It stays in your Statement History — nothing is deleted."
          confirmLabel="Replace"
          onConfirm={confirmSupersede}
          onCancel={() => setConfirmingSupersede(false)}
        />
      )}
    </>
  );
}

function ImportSummaryScreen({
  summary,
  onDone,
  onImportAnother,
}: {
  summary: ImportSummary;
  onDone: () => void;
  onImportAnother: () => void;
}) {
  const categoryEntries = Object.entries(summary.categoriesAssigned).sort((a, b) => b[1] - a[1]);
  const account = summary.account;
  const periodStart = summary.statementPeriodStart ? formatDate(summary.statementPeriodStart) : null;
  const periodEnd = summary.statementPeriodEnd ? formatDate(summary.statementPeriodEnd) : null;
  const durationLabel = summary.importDurationMs < 1000 ? `${summary.importDurationMs} ms` : `${(summary.importDurationMs / 1000).toFixed(1)} s`;

  return (
    <div className="bg-card rounded-xl2 shadow-card border border-border p-6 max-w-xl">
      <div className="flex items-center gap-3 mb-5">
        <div className="w-10 h-10 rounded-full bg-success-bg flex items-center justify-center flex-shrink-0">
          <CheckCircle2 size={20} className="text-success" />
        </div>
        <div>
          <h2 className="font-semibold text-ink">Import complete</h2>
          <p className="text-xs text-muted">Your Dashboard, Accounts and Transactions have been refreshed.</p>
        </div>
      </div>

      {/* Professional import summary (PRD's "Import Experience") -- everything about which
          account this went into, at a glance, without a second trip to the Accounts page. */}
      {account && (
        <div className="bg-bg border border-border rounded-xl p-4 mb-5">
          <div className="flex items-center gap-3 mb-3">
            <BankLogo bank={account.bank} size={36} />
            <div className="min-w-0">
              <p className="text-sm font-semibold text-ink truncate">{account.bank.officialName ?? account.name}</p>
              <p className="text-xs text-muted truncate">
                {account.name}
                {account.accountHolderName ? ` • ${account.accountHolderName}` : ''}
                {/* Was a hard-coded "•••• ••••" with no way to see the number at all -- the exact
                    opposite failure to the detected-account field above, on the same page. */}
                {account.accountNumberMasked && (
                  <>
                    {' • '}
                    <MaskedAccountNumber value={account.accountNumberMasked} />
                  </>
                )}
              </p>
            </div>
          </div>
          <div className="grid grid-cols-2 gap-2 text-xs">
            {(periodStart || periodEnd) && (
              <p className="text-muted">Statement period: <span className="text-ink font-medium">{periodStart ?? '—'} – {periodEnd ?? '—'}</span></p>
            )}
            {summary.statementOpeningBalance !== null && (
              <p className="text-muted">Opening balance: <span className="text-ink font-medium">{fmt(summary.statementOpeningBalance)}</span></p>
            )}
            {summary.statementClosingBalance !== null && (
              <p className="text-muted">Closing balance: <span className="text-ink font-medium">{fmt(summary.statementClosingBalance)}</span></p>
            )}
            <p className="text-muted">Total credits: <span className="text-success font-medium">{fmt(summary.totalCredits)}</span></p>
            <p className="text-muted">Total debits: <span className="text-danger font-medium">{fmt(summary.totalDebits)}</span></p>
            <p className="text-muted flex items-center gap-1"><Clock size={11} /> {durationLabel} • {summary.source} import</p>
          </div>
        </div>
      )}

      <div className="grid grid-cols-2 gap-3 mb-5">
        <SummaryStat label="Transactions imported" value={summary.imported} />
        <SummaryStat label="Skipped" value={summary.skipped} />
        <SummaryStat label="Duplicates detected" value={summary.duplicatesDetected} />
        <SummaryStat label="Transfers identified" value={summary.transfersIdentified} />
        <SummaryStat label="New merchants learned" value={summary.newMerchantsLearned} />
        <SummaryStat label="Accounts created" value={summary.accountsCreated.length} />
      </div>

      {summary.accountsCreated.length > 0 && (
        <p className="text-xs text-muted mb-3">
          Created: <span className="text-ink font-medium">{formatProductsCreated(summary)}</span>
        </p>
      )}

      {categoryEntries.length > 0 && (
        <div className="mb-4">
          <p className="text-xs uppercase text-muted mb-2">Categories assigned</p>
          <div className="space-y-1">
            {categoryEntries.map(([name, count]) => (
              <div key={name} className="flex justify-between text-sm">
                <span className="text-ink">{name}</span>
                <span className="text-muted">{count}</span>
              </div>
            ))}
          </div>
        </div>
      )}

      <StatementWarnings summary={summary} />

      <div className="flex gap-3">
        <Button onClick={onDone}>Go to Dashboard</Button>
        <Button variant="secondary" onClick={onImportAnother}>Import another statement</Button>
      </div>
    </div>
  );
}

// Multi-account counterpart to ImportSummaryScreen -- one compact card per account confirmed
// together from a single multi-section PDF upload (see confirmMultiImport), plus one shared
// footer instead of duplicating the full single-account summary layout N times.
function MultiImportSummaryScreen({
  summaries,
  onDone,
  onImportAnother,
}: {
  summaries: ImportSummary[];
  onDone: () => void;
  onImportAnother: () => void;
}) {
  const totalImported = summaries.reduce((sum, s) => sum + s.imported, 0);
  const totalSkipped = summaries.reduce((sum, s) => sum + s.skipped, 0);
  const accountsCreated = summaries.flatMap((s) => s.accountsCreated);

  return (
    <div className="bg-card rounded-xl2 shadow-card border border-border p-6 max-w-xl">
      <div className="flex items-center gap-3 mb-5">
        <div className="w-10 h-10 rounded-full bg-success-bg flex items-center justify-center flex-shrink-0">
          <CheckCircle2 size={20} className="text-success" />
        </div>
        <div>
          <h2 className="font-semibold text-ink">Import complete — {summaries.length} accounts</h2>
          <p className="text-xs text-muted">Your Dashboard, Accounts and Transactions have been refreshed.</p>
        </div>
      </div>

      <div className="space-y-3 mb-5">
        {summaries.map((summary, i) => {
          const account = summary.account;
          return (
            <div key={i} data-testid={`summary-account-${i}`} className="bg-bg border border-border rounded-xl p-4">
              {account && (
                <div className="flex items-center gap-3 mb-3">
                  <BankLogo bank={account.bank} size={32} />
                  <div className="min-w-0">
                    <p className="text-sm font-semibold text-ink truncate">{account.bank.officialName ?? account.name}</p>
                    <p className="text-xs text-muted truncate">
                      {account.name}
                      {account.accountHolderName ? ` • ${account.accountHolderName}` : ''}
                    </p>
                  </div>
                </div>
              )}
              <div className="grid grid-cols-2 gap-2 text-xs mb-3">
                <p className="text-muted">Imported: <span className="text-ink font-medium">{summary.imported}</span></p>
                <p className="text-muted">Skipped: <span className="text-ink font-medium">{summary.skipped}</span></p>
                {summary.statementClosingBalance !== null && (
                  <p className="text-muted">Closing balance: <span className="text-ink font-medium">{fmt(summary.statementClosingBalance)}</span></p>
                )}
                <p className="text-muted">Total credits: <span className="text-success font-medium">{fmt(summary.totalCredits)}</span></p>
                <p className="text-muted">Total debits: <span className="text-danger font-medium">{fmt(summary.totalDebits)}</span></p>
              </div>
              {/* Each account's own ImportSummary carries its own warnings and, as of Phase 4, its
                  own duplicateOfStatementId -- a composite statement's savings section can
                  duplicate an existing period while its credit-card section does not, so this has
                  to render (and offer to supersede) per account, not once for the whole file. */}
              <StatementWarnings summary={summary} />
            </div>
          );
        })}
      </div>

      <div className="grid grid-cols-2 gap-3 mb-5">
        <SummaryStat label="Total transactions imported" value={totalImported} />
        <SummaryStat label="Total skipped" value={totalSkipped} />
      </div>

      {accountsCreated.length > 0 && (
        <p className="text-xs text-muted mb-5">
          Created: <span className="text-ink font-medium">{accountsCreated.join(', ')}</span>
        </p>
      )}

      <div className="flex gap-3">
        <Button onClick={onDone}>Go to Dashboard</Button>
        <Button variant="secondary" onClick={onImportAnother}>Import another statement</Button>
      </div>
    </div>
  );
}

function SummaryStat({ label, value }: { label: string; value: number }) {
  return (
    <div className="bg-bg rounded-lg p-3">
      <p className="text-lg font-bold text-ink">{value}</p>
      <p className="text-[11px] text-muted">{label}</p>
    </div>
  );
}
