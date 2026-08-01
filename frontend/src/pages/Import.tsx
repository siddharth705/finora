import { useEffect, useRef, useState, type Dispatch, type SetStateAction } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useQueryClient, type QueryClient } from '@tanstack/react-query';
import { CheckCircle2, UploadCloud, AlertTriangle, Clock, FileText, FileSpreadsheet } from 'lucide-react';
import { importApi, statementImportsApi, categoriesApi, accountsApi } from '../api/endpoints';
import { BankLogo } from '../components/BankLogo';
import type { Account, DetectedAccountInfo, ImportSummary, ReimportResult, StagedAccountSection, StagedRow } from '../types';

type Step = 'upload' | 'review' | 'summary';
type AccountChoice = 'existing' | 'new';

interface ReimportNavState {
  reimportId: string;
  staging: ReimportResult['staging'];
  accountId: string;
  accountName: string;
}

// Per-account review state for the multi-account case (a PDF whose upload detected more than one
// account section, e.g. an HSBC-style composite statement) -- one of these per detected
// StagedAccountSection, holding exactly the same fields the single-account path already tracks as
// flat top-level state, just namespaced per section instead.
interface SectionState {
  detectedAccount: DetectedAccountInfo;
  rows: StagedRow[];
  included: boolean[];
  chosenCategory: string[];
  accountChoice: AccountChoice;
  selectedAccountId: string;
  newName: string;
  newType: Account['accountType'];
  newOpeningBalance: string;
  newCreditLimit: string;
  newDueDate: string;
}

function initialSectionState(section: StagedAccountSection, existingAccounts: Account[]): SectionState {
  const detected = section.detectedAccount;
  return {
    detectedAccount: detected,
    rows: section.rows,
    included: section.rows.map((r) => !r.likelyDuplicate),
    chosenCategory: section.rows.map((r) => r.suggestedCategory),
    accountChoice: existingAccounts.length > 0 ? 'existing' : 'new',
    selectedAccountId: existingAccounts.length > 0 ? existingAccounts[0].id : '',
    newName: detected.suggestedName,
    newType: detected.suggestedAccountType,
    newOpeningBalance: detected.openingBalance != null ? String(detected.openingBalance) : '',
    newCreditLimit: detected.creditLimit != null ? String(detected.creditLimit) : '',
    newDueDate: detected.paymentDueDate ?? '',
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
  queryClient.invalidateQueries({ queryKey: ['dashboard-summary'] });
  queryClient.invalidateQueries({ queryKey: ['accounts'] });
  queryClient.invalidateQueries({ queryKey: ['transactions'] });
  queryClient.invalidateQueries({ queryKey: ['recent-transactions'] });
  queryClient.invalidateQueries({ queryKey: ['goals'] });
  queryClient.invalidateQueries({ queryKey: ['insights'] });
  queryClient.invalidateQueries({ queryKey: ['statement-imports'] });
  queryClient.invalidateQueries({ queryKey: ['budgets'] });
  queryClient.invalidateQueries({ queryKey: ['report-months'] });
  queryClient.invalidateQueries({ queryKey: ['report'] });
}

export default function Import() {
  const fileInput = useRef<HTMLInputElement>(null);
  const navigate = useNavigate();
  const location = useLocation();
  const queryClient = useQueryClient();

  // "Re-import Statement" (from the Statement History page) lands here with the original file's
  // staging result already computed server-side — see StatementImportService.reimport(). There's
  // no browser File object in this case (the bytes never left the server), so this page skips
  // straight to the review step, locked to the account the statement already belongs to.
  const reimportState = (location.state as ReimportNavState | null) ?? null;

  const [step, setStep] = useState<Step>('upload');
  const [error, setError] = useState<string | null>(null);

  // Staged rows
  const [rows, setRows] = useState<StagedRow[]>([]);
  const [included, setIncluded] = useState<boolean[]>([]);
  const [chosenCategory, setChosenCategory] = useState<string[]>([]);
  const [categories, setCategories] = useState<string[]>([]);

  // Target account: an existing one, or a new one built from what the statement told us
  const [existingAccounts, setExistingAccounts] = useState<Account[]>([]);
  const [detectedAccount, setDetectedAccount] = useState<DetectedAccountInfo | null>(null);
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

  // Set only for a multi-account PDF upload (see SectionState above) -- null the rest of the
  // time, and the single flat rows/detectedAccount/etc. state above is what's used instead.
  const [multiSections, setMultiSections] = useState<SectionState[] | null>(null);

  // 0-100 while a file is uploading, null otherwise -- purely the network-transfer portion (see
  // ProgressCallback's own doc comment in endpoints.ts), so 100% means "processing," not "done."
  const [uploadProgress, setUploadProgress] = useState<number | null>(null);

  // ADR-0002: the backend now persists the staged file/rows server-side (ImportSession), keyed
  // by this id -- confirmImport() sends it instead of re-uploading the original file a second
  // time, which is what used to require holding onto the File object in state after staging.
  const [sessionId, setSessionId] = useState<string | null>(null);
  // Purely cosmetic — shown as a small badge in the review step so it's obvious at a glance which
  // path staged this session, same spirit as the "Detected {bank}" callout below.
  const [fileFormat, setFileFormat] = useState<'CSV' | 'PDF' | null>(null);

  useEffect(() => {
    // Non-critical background loads -- a failure here (e.g. a network blip, an expired token
    // mid-session) shouldn't crash this effect as an unhandled rejection; the page still works
    // with an empty category list / existing-account list, just with fewer detected defaults.
    categoriesApi.list().then((cats) => setCategories(cats.map((c) => c.name))).catch((e) => console.error('Failed to load categories', e));
    accountsApi.list().then(setExistingAccounts).catch((e) => console.error('Failed to load accounts', e));

    if (reimportState) {
      setRows(reimportState.staging.rows);
      setIncluded(reimportState.staging.rows.map((r) => !r.likelyDuplicate));
      setChosenCategory(reimportState.staging.rows.map((r) => r.suggestedCategory));
      setDetectedAccount(reimportState.staging.detectedAccount);
      setAccountChoice('existing');
      setSelectedAccountId(reimportState.accountId);
      setStep('review');
    }
    // Only ever run once on mount — reimportState comes from router state at navigation time,
    // not something that changes while this page is open.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function handleFile(file: File) {
    setError(null);
    setMultiSections(null); // clear any previous multi-account run before staging a new file
    const lowerName = file.name.toLowerCase();
    const isPdf = lowerName.endsWith('.pdf');
    const isCsv = lowerName.endsWith('.csv');
    if (!isPdf && !isCsv) {
      setError('Please upload a .csv or .pdf bank/credit card statement.');
      return;
    }
    setUploadProgress(0);
    try {
      const res = isPdf
        ? await importApi.stagePdf(file, setUploadProgress)
        : await importApi.stageCsv(file, setUploadProgress);
      setSessionId(res.sessionId);
      setFileFormat(isPdf ? 'PDF' : 'CSV');

      // Multi-account PDF (e.g. an HSBC-style composite statement bundling a savings account and
      // a credit-card account in one file) -- res.staging is null here, res.sections is what's
      // populated instead. See PdfStagingSessionResult's own doc comment in endpoints.ts.
      if ('multiAccount' in res && res.multiAccount && res.sections) {
        setMultiSections(res.sections.map((s) => initialSectionState(s, existingAccounts)));
        setStep('review');
        return;
      }

      const staging = res.staging!; // guaranteed non-null whenever multiAccount is false/absent
      setRows(staging.rows);
      setIncluded(staging.rows.map((r) => !r.likelyDuplicate));
      setChosenCategory(staging.rows.map((r) => r.suggestedCategory));
      setDetectedAccount(staging.detectedAccount);

      // Pre-fill the new-account form from whatever the statement told us — every field here
      // is editable before anything is created, since detection is best-effort by design.
      setNewName(staging.detectedAccount.suggestedName);
      setNewType(staging.detectedAccount.suggestedAccountType);
      setNewOpeningBalance(staging.detectedAccount.openingBalance != null ? String(staging.detectedAccount.openingBalance) : '');
      setNewCreditLimit(staging.detectedAccount.creditLimit != null ? String(staging.detectedAccount.creditLimit) : '');
      setNewDueDate(staging.detectedAccount.paymentDueDate ?? '');

      // Default to "new account" only when there's genuinely nothing to reuse — otherwise
      // default to the account the file's own signals most plausibly matches, falling back to
      // the first existing account.
      if (existingAccounts.length > 0) {
        setAccountChoice('existing');
        setSelectedAccountId(existingAccounts[0].id);
      } else {
        setAccountChoice('new');
      }

      setStep('review');
    } catch (e: any) {
      setError(e.response?.data?.message ?? (isPdf ? 'Could not parse this PDF.' : 'Could not parse this CSV.'));
    } finally {
      setUploadProgress(null);
    }
  }

  async function confirmImport() {
    if (!reimportState && !sessionId) return;
    setConfirming(true);
    setError(null);
    try {
      const rowPayload = rows.map((r, i) => ({
        date: r.date,
        description: r.description,
        amount: r.amount,
        type: r.type,
        category: chosenCategory[i],
        include: included[i],
        categorySource: r.categorySource,
        // Without this, decision_rule_id would always land null through the normal UI flow --
        // the backend derives decisionSource from categorySource alone, but the specific rule
        // link (for a future "why was this categorized this way" screen) only survives if the
        // staged ruleId is echoed back here, same as categorySource already was.
        ruleId: r.ruleId,
        likelyDuplicate: r.likelyDuplicate,
      }));

      const existingAccountId = accountChoice === 'existing' ? selectedAccountId : null;
      const newAccount =
        accountChoice === 'new'
          ? {
              name: newName.trim() || 'Imported Account',
              accountType: newType,
              openingBalance: newOpeningBalance ? parseFloat(newOpeningBalance) : null,
              creditLimit: newType === 'CREDIT_CARD' && newCreditLimit ? parseFloat(newCreditLimit) : null,
              dueDate: newType === 'CREDIT_CARD' && newDueDate ? newDueDate : null,
              // These two were already detected into `detectedAccount` and shown (disabled) in
              // the review step below, but never actually made it into this payload — the
              // account got created without them even when the statement clearly carried both.
              accountHolderName: detectedAccount?.accountHolderName ?? null,
              accountNumberMasked: detectedAccount?.accountNumberMasked ?? null,
              // Detected bank identifier -- lets the new account's logo/color render correctly
              // even if the user renames the account away from the bank's official name below.
              bankId: detectedAccount?.bank.id ?? null,
              branchName: detectedAccount?.branchName ?? null,
              ifscCode: detectedAccount?.ifscCode ?? null,
            }
          : null;

      const result = reimportState
        ? await statementImportsApi.confirmReimport(reimportState.reimportId, {
            rows: rowPayload,
            existingAccountId,
            statementOpeningBalance: detectedAccount?.openingBalance ?? null,
            statementClosingBalance: detectedAccount?.closingBalance ?? null,
          })
        : await importApi.confirm({
            sessionId: sessionId!,
            rows: rowPayload,
            existingAccountId,
            newAccount,
            statementOpeningBalance: detectedAccount?.openingBalance ?? null,
            statementClosingBalance: detectedAccount?.closingBalance ?? null,
          });
      setSummary(result);
      setStep('summary');
      invalidateImportRelatedQueries(queryClient);
    } catch (e: any) {
      setError(e.response?.data?.message ?? 'Could not complete the import.');
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
    setError(null);
    try {
      const sections = multiSections.map((s) => {
        const rowPayload = s.rows.map((r, i) => ({
          date: r.date,
          description: r.description,
          amount: r.amount,
          type: r.type,
          category: s.chosenCategory[i],
          include: s.included[i],
          categorySource: r.categorySource,
          ruleId: r.ruleId,
          likelyDuplicate: r.likelyDuplicate,
        }));
        const existingAccountId = s.accountChoice === 'existing' ? s.selectedAccountId : null;
        const newAccount =
          s.accountChoice === 'new'
            ? {
                name: s.newName.trim() || 'Imported Account',
                accountType: s.newType,
                openingBalance: s.newOpeningBalance ? parseFloat(s.newOpeningBalance) : null,
                creditLimit: s.newType === 'CREDIT_CARD' && s.newCreditLimit ? parseFloat(s.newCreditLimit) : null,
                dueDate: s.newType === 'CREDIT_CARD' && s.newDueDate ? s.newDueDate : null,
                accountHolderName: s.detectedAccount.accountHolderName ?? null,
                accountNumberMasked: s.detectedAccount.accountNumberMasked ?? null,
                bankId: s.detectedAccount.bank.id ?? null,
                branchName: s.detectedAccount.branchName ?? null,
                ifscCode: s.detectedAccount.ifscCode ?? null,
              }
            : null;
        return {
          rows: rowPayload,
          existingAccountId,
          newAccount,
          statementOpeningBalance: s.detectedAccount.openingBalance ?? null,
          statementClosingBalance: s.detectedAccount.closingBalance ?? null,
        };
      });

      const result = await importApi.confirmMulti({ sessionId, sections });
      setMultiSummary(result.perAccount);
      setStep('summary');
      invalidateImportRelatedQueries(queryClient);
    } catch (e: any) {
      setError(e.response?.data?.message ?? 'Could not complete the import.');
    } finally {
      setConfirming(false);
    }
  }

  function startOver() {
    setStep('upload');
    setRows([]);
    setSummary(null);
    setMultiSummary(null);
    setMultiSections(null);
    setUploadProgress(null);
    setError(null);
    setFileFormat(null);
    accountsApi.list().then(setExistingAccounts).catch((e) => console.error('Failed to load accounts', e));
  }

  if (step === 'summary' && summary) {
    return <ImportSummaryScreen summary={summary} onDone={() => navigate('/app')} onImportAnother={startOver} />;
  }
  if (step === 'summary' && multiSummary) {
    return <MultiImportSummaryScreen summaries={multiSummary} onDone={() => navigate('/app')} onImportAnother={startOver} />;
  }

  return (
    <div className="space-y-4">
      {step === 'upload' && (
        <div
          data-testid="statement-dropzone"
          className={`bg-card rounded p-8 shadow border-2 border-dashed border-border text-center ${uploadProgress === null ? 'cursor-pointer' : 'cursor-default'}`}
          onClick={() => uploadProgress === null && fileInput.current?.click()}
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
            onChange={(e) => e.target.files?.[0] && handleFile(e.target.files[0])}
          />
        </div>
      )}

      {error && (
        <p className="text-danger text-sm flex items-center gap-2">
          <AlertTriangle size={14} /> {error}
        </p>
      )}

      {step === 'review' && multiSections && (
        <>
          {/* Multi-account PDF (e.g. an HSBC-style composite statement bundling a savings
              account and a credit-card account in one file) -- one card per detected section,
              each independently reviewable, all confirmed together via one button below. */}
          <div className="bg-card rounded-xl2 shadow-card border border-border p-5">
            <h2 className="font-semibold text-ink text-sm mb-1">
              This statement covers {multiSections.length} accounts
            </h2>
            <p className="text-xs text-muted">
              We found {multiSections.length} separate accounts in this file — review each one below, then confirm
              them all together.
            </p>
          </div>

          {multiSections.map((section, sectionIndex) => (
            <div key={sectionIndex} className="bg-card rounded-xl2 shadow-card border border-border p-5 space-y-4">
              <h3 className="font-semibold text-ink text-sm">
                Account {sectionIndex + 1} of {multiSections.length}
                {section.detectedAccount.bank.id !== 'OTHER' && ` — ${section.detectedAccount.bank.officialName}`}
              </h3>

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
                included={section.included}
                setIncluded={(updater) => updateSection(setMultiSections, sectionIndex, { included: updater(section.included) })}
                chosenCategory={section.chosenCategory}
                setChosenCategory={(updater) => updateSection(setMultiSections, sectionIndex, { chosenCategory: updater(section.chosenCategory) })}
                categories={categories}
              />
            </div>
          ))}

          <div className="bg-card rounded shadow p-4">
            <button
              onClick={confirmMultiImport}
              disabled={confirming || multiSections.some((s) => s.accountChoice === 'existing' && !s.selectedAccountId)}
              className="bg-primary text-white hover:bg-primary-dark px-4 py-2 rounded-lg text-xs font-semibold disabled:opacity-50"
            >
              {confirming ? 'Importing…' : `Confirm All ${multiSections.length} Accounts`}
            </button>
          </div>
        </>
      )}

      {step === 'review' && !multiSections && (
        <>
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
            </p>
            <TransactionPreviewTable
              rows={rows}
              included={included}
              setIncluded={setIncluded}
              chosenCategory={chosenCategory}
              setChosenCategory={setChosenCategory}
              categories={categories}
            />
            <button
              onClick={confirmImport}
              disabled={
                confirming ||
                (!reimportState && !sessionId) ||
                (!reimportState && accountChoice === 'existing' && !selectedAccountId)
              }
              className="bg-primary text-white hover:bg-primary-dark px-4 py-2 rounded-lg text-xs font-semibold disabled:opacity-50 mt-4"
            >
              {confirming ? 'Importing…' : 'Confirm Import'}
            </button>
          </div>
        </>
      )}
    </div>
  );
}

// Small helper for updating one section's state within the multiSections array by index --
// used throughout the multi-account review UI above instead of hand-rolling the same
// map-and-replace pattern at every call site.
function updateSection(
  setMultiSections: Dispatch<SetStateAction<SectionState[] | null>>,
  index: number,
  patch: Partial<SectionState>,
) {
  setMultiSections((prev) => (prev ? prev.map((s, i) => (i === index ? { ...s, ...patch } : s)) : prev));
}

// The existing-vs-new account picker + new-account detail fields -- shared between the
// single-account review step and each account card in the multi-account review step, so the two
// stay pixel-identical instead of drifting apart as separate copies.
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
          {detectedAccount && detectedAccount.bank.id !== 'OTHER' && (
            <div className="md:col-span-2 flex items-center gap-2.5 bg-primary-light border border-primary/20 rounded-lg px-3 py-2">
              <BankLogo bank={detectedAccount.bank} size={28} />
              <p className="text-xs text-ink">
                Detected <span className="font-semibold">{detectedAccount.bank.officialName}</span> from this statement.
              </p>
            </div>
          )}
          <div>
            <label className="block text-xs uppercase text-muted mb-1">Account name</label>
            <input value={newName} onChange={(e) => setNewName(e.target.value)} className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm w-full" />
          </div>
          <div>
            <label className="block text-xs uppercase text-muted mb-1">Account type</label>
            <select value={newType} onChange={(e) => setNewType(e.target.value as Account['accountType'])} className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm w-full">
              <option value="SAVINGS">Savings</option>
              <option value="CREDIT_CARD">Credit Card</option>
              <option value="WALLET">Wallet</option>
              <option value="INVESTMENT">Investment</option>
            </select>
          </div>
          <div>
            <label className="block text-xs uppercase text-muted mb-1">
              Opening balance {detectedAccount?.openingBalance != null && <span className="normal-case text-primary">(detected)</span>}
            </label>
            <input type="number" value={newOpeningBalance} onChange={(e) => setNewOpeningBalance(e.target.value)} className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm w-full" />
          </div>
          {detectedAccount?.accountHolderName && (
            <div>
              <label className="block text-xs uppercase text-muted mb-1">Account holder (detected)</label>
              <input value={detectedAccount.accountHolderName} disabled className="border border-border rounded-lg px-3 py-2 text-sm w-full bg-bg text-muted" />
            </div>
          )}
          {detectedAccount?.accountNumberMasked && (
            <div>
              <label className="block text-xs uppercase text-muted mb-1">Account number (detected)</label>
              <input value={detectedAccount.accountNumberMasked} disabled className="border border-border rounded-lg px-3 py-2 text-sm w-full bg-bg text-muted" />
            </div>
          )}
          {detectedAccount?.branchName && (
            <div>
              <label className="block text-xs uppercase text-muted mb-1">Branch (detected)</label>
              <input value={detectedAccount.branchName} disabled className="border border-border rounded-lg px-3 py-2 text-sm w-full bg-bg text-muted" />
            </div>
          )}
          {detectedAccount?.ifscCode && (
            <div>
              <label className="block text-xs uppercase text-muted mb-1">IFSC code (detected)</label>
              <input value={detectedAccount.ifscCode} disabled className="border border-border rounded-lg px-3 py-2 text-sm w-full bg-bg text-muted" />
            </div>
          )}
          {newType === 'CREDIT_CARD' && (
            <>
              <div>
                <label className="block text-xs uppercase text-muted mb-1">Credit limit</label>
                <input type="number" value={newCreditLimit} onChange={(e) => setNewCreditLimit(e.target.value)} className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm w-full" />
              </div>
              <div>
                <label className="block text-xs uppercase text-muted mb-1">Payment due date</label>
                <input type="date" value={newDueDate} onChange={(e) => setNewDueDate(e.target.value)} className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm w-full" />
              </div>
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
  included,
  setIncluded,
  chosenCategory,
  setChosenCategory,
  categories,
}: {
  rows: StagedRow[];
  included: boolean[];
  setIncluded: (updater: (arr: boolean[]) => boolean[]) => void;
  chosenCategory: string[];
  setChosenCategory: (updater: (arr: string[]) => string[]) => void;
  categories: string[];
}) {
  return (
    <table className="w-full text-xs font-mono mb-4">
      <thead>
        <tr className="text-left text-[10px] uppercase text-gray-500">
          <th className="p-1"></th><th className="p-1">Date</th><th className="p-1">Description</th>
          <th className="p-1">Amount</th><th className="p-1">Category</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((r, i) => (
          <tr key={i} className="border-b border-dashed">
            <td className="p-1">
              <input
                type="checkbox"
                checked={included[i]}
                onChange={(e) => setIncluded((arr) => arr.map((v, j) => (j === i ? e.target.checked : v)))}
              />
            </td>
            <td className="p-1">{r.date}</td>
            <td className="p-1">
              {r.description}
              {r.likelyDuplicate && <span className="text-danger text-[10px] uppercase ml-1">duplicate</span>}
              {r.categorySource === 'default' && (
                <span className="text-[10px] uppercase ml-1" style={{ color: '#d97706' }}>low confidence</span>
              )}
            </td>
            <td className="p-1">₹{r.amount}</td>
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
  const periodStart = summary.statementPeriodStart ? new Date(summary.statementPeriodStart).toLocaleDateString('en-IN', { year: 'numeric', month: 'short', day: 'numeric' }) : null;
  const periodEnd = summary.statementPeriodEnd ? new Date(summary.statementPeriodEnd).toLocaleDateString('en-IN', { year: 'numeric', month: 'short', day: 'numeric' }) : null;
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
                {account.accountNumberMasked ? ` • •••• ••••` : ''}
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
          Created: <span className="text-ink font-medium">{summary.accountsCreated.join(', ')}</span>
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

      {summary.warnings.length > 0 && (
        <div className="bg-warning-bg border border-warning rounded-lg p-3 mb-4">
          {summary.warnings.map((w, i) => (
            <p key={i} className="text-xs text-ink flex items-start gap-2">
              <AlertTriangle size={13} className="text-warning flex-shrink-0 mt-0.5" /> {w}
            </p>
          ))}
        </div>
      )}

      <div className="flex gap-3">
        <button onClick={onDone} className="bg-primary text-white hover:bg-primary-dark px-4 py-2 rounded-lg text-xs font-semibold">
          Go to Dashboard
        </button>
        <button onClick={onImportAnother} className="border border-border text-ink px-4 py-2 rounded-lg text-xs font-semibold">
          Import another statement
        </button>
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
            <div key={i} className="bg-bg border border-border rounded-xl p-4">
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
              <div className="grid grid-cols-2 gap-2 text-xs">
                <p className="text-muted">Imported: <span className="text-ink font-medium">{summary.imported}</span></p>
                <p className="text-muted">Skipped: <span className="text-ink font-medium">{summary.skipped}</span></p>
                {summary.statementClosingBalance !== null && (
                  <p className="text-muted">Closing balance: <span className="text-ink font-medium">{fmt(summary.statementClosingBalance)}</span></p>
                )}
                <p className="text-muted">Total credits: <span className="text-success font-medium">{fmt(summary.totalCredits)}</span></p>
                <p className="text-muted">Total debits: <span className="text-danger font-medium">{fmt(summary.totalDebits)}</span></p>
              </div>
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
        <button onClick={onDone} className="bg-primary text-white hover:bg-primary-dark px-4 py-2 rounded-lg text-xs font-semibold">
          Go to Dashboard
        </button>
        <button onClick={onImportAnother} className="border border-border text-ink px-4 py-2 rounded-lg text-xs font-semibold">
          Import another statement
        </button>
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
