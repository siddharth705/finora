import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Import from './Import';
import { AuthProvider } from '../context/AuthContext';
import { importApi, importJobsApi, statementImportsApi, categoriesApi, accountsApi, type ImportJobProgress } from '../api/endpoints';
import type { Account, StagedAccountSection } from '../types';
import { PDF_PASSWORD_REQUIRED, PDF_PASSWORD_INVALID, NO_HEADER_DETECTED, NO_TRANSACTIONS_FOUND, SCANNED_OCR_REQUIRED, CORRUPT_PDF, IMPORT_SESSION_ALREADY_CONFIRMED } from '../api/errorCodes';
import { IMPORT_FAILURE_MESSAGES } from '../api/importFailureMessages';
import type { DetectedAccountInfo } from '../types';

// Only the upload step's file-routing logic is under test here (stagePdf vs stageCsv, and the
// unsupported-file-type guard) -- everything else Import.tsx touches (categories/accounts lists,
// the review step's account form, confirm/summary) is exercised by hand during review, not here.
vi.mock('../api/endpoints', () => ({
  importApi: {
    stageCsv: vi.fn(),
    stagePdf: vi.fn(),
    confirm: vi.fn(),
    confirmMulti: vi.fn(),
    listSessions: vi.fn(),
    getSession: vi.fn(),
    discardSession: vi.fn(),
  },
  statementImportsApi: {
    confirmReimport: vi.fn(),
    remove: vi.fn(),
    supersede: vi.fn(),
  },
  categoriesApi: {
    list: vi.fn(),
  },
  accountsApi: {
    list: vi.fn(),
  },
  // The asynchronous path. Availability defaults to false for the suites below, which are all about
  // the synchronous flow -- so they keep exercising it rather than silently becoming tests of the
  // queue. The queue's own behaviour is asserted in "Import — queued imports" at the bottom, which
  // turns it on for itself.
  importJobsApi: {
    availability: vi.fn(),
    submit: vi.fn(),
    progress: vi.fn(),
    timeline: vi.fn(),
    recent: vi.fn(),
    cancel: vi.fn(),
  },
}));

const detectedAccount: DetectedAccountInfo = {
  suggestedName: 'Imported Account',
  suggestedAccountType: 'SAVINGS',
  openingBalance: null,
  closingBalance: null,
  statementPeriodStart: null,
  statementPeriodEnd: null,
  accountNumberMasked: null,
  creditLimit: null,
  totalAmountDue: null,
  paymentDueDate: null,
  accountHolderName: null,
  branchName: null,
  ifscCode: null,
  detectedProduct: 'SAVINGS',
  productConfidence: 0.85,
  productNeedsReview: false,
  productEvidence: [],
  productIdentityHash: null,
  principalAmount: null,
  interestRate: null,
  maturityDate: null,
  maturityAmount: null,
  installmentAmount: null,
  installmentsPaid: null,
  installmentsTotal: null,
  bank: {
    id: 'OTHER',
    officialName: null,
    shortName: 'Other',
    colorHex: '#000000',
    initials: 'OT',
    logoPath: '',
    category: null,
    websiteUrl: null,
    ifscPrefix: null,
    supportedAccountTypes: [],
  },
};

function stagingResultWith(overrides: Partial<{ sessionId: string }> = {}) {
  return {
    sessionId: overrides.sessionId ?? 'session-1',
    // multiAccount/sections only exist on the PDF staging response shape, but included here too
    // (harmless for the CSV mock) so one helper satisfies both importApi.stageCsv and
    // importApi.stagePdf's declared return types.
    multiAccount: false,
    sections: null,
    staging: { rows: [], totalParsed: 0, flaggedDuplicates: 0, detectedAccount, unparseableRows: [] },
  };
}

/**
 * File-scope, so every suite below starts from a deployment WITHOUT the queue.
 *
 * The page asks about availability on mount, and an unmocked answer would make the very first
 * render of every test throw. Defaulting it to false also keeps the intent honest: the suites in
 * this file are about the synchronous flow, and if the queue silently became the default they would
 * carry on passing while testing a path they were never written for.
 */
beforeEach(() => {
  vi.mocked(importJobsApi.availability).mockReset().mockResolvedValue({ asyncImportAvailable: false });
  vi.mocked(importJobsApi.submit).mockReset();
  vi.mocked(importJobsApi.progress).mockReset();
  vi.mocked(importJobsApi.cancel).mockReset();
  // No unfinished sessions by default -- this query fires unconditionally on every mount, and the
  // suites in this file are about the upload/review/confirm flow, not the "continue previous
  // import" section, which has its own describe block below.
  vi.mocked(importApi.listSessions).mockReset().mockResolvedValue([]);
});

function renderImport() {
  const queryClient = new QueryClient();
  return {
    ...render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <AuthProvider>
            <Import />
          </AuthProvider>
        </MemoryRouter>
      </QueryClientProvider>
    ),
    queryClient,
  };
}

function csvFile(name = 'statement.csv') {
  return new File(['date,description,amount\n'], name, { type: 'text/csv' });
}

function pdfFile(name = 'statement.pdf') {
  return new File(['%PDF-1.4'], name, { type: 'application/pdf' });
}

/**
 * Picking a PDF no longer uploads it -- it opens the password panel first, because most Indian
 * bank e-statements arrive protected and asking up front beats a guaranteed failed upload. This
 * helper does both halves so tests that only care about what happens AFTER staging stay readable.
 * CSV is unaffected and still uploads on selection, which is why there's no equivalent for it.
 */
async function pickAndUploadPdf(user: ReturnType<typeof userEvent.setup>, file = pdfFile(), password?: string) {
  await user.upload(screen.getByTestId('statement-file-input'), file);
  if (password) await user.type(screen.getByLabelText(/statement password/i), password);
  await user.click(screen.getByRole('button', { name: /upload statement/i }));
}

describe('Import — file-type routing', () => {
  beforeEach(() => {
    vi.mocked(importApi.stageCsv).mockReset().mockResolvedValue(stagingResultWith());
    vi.mocked(importApi.stagePdf).mockReset().mockResolvedValue(stagingResultWith());
    vi.mocked(categoriesApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(accountsApi.list).mockReset().mockResolvedValue([]);
  });

  it('stages a .csv file through stageCsv, not stagePdf', async () => {
    const user = userEvent.setup();
    renderImport();

    await user.upload(screen.getByTestId('statement-file-input'), csvFile());

    await waitFor(() => expect(importApi.stageCsv).toHaveBeenCalledTimes(1));
    // Second argument is the upload-progress callback (setUploadProgress) -- not asserted here,
    // just the file itself, since it varies by render (a bound React state setter).
    expect(importApi.stageCsv).toHaveBeenCalledWith(expect.objectContaining({ name: 'statement.csv' }), expect.any(Function));
    expect(importApi.stagePdf).not.toHaveBeenCalled();
  });

  it('stages a .pdf file through stagePdf, not stageCsv', async () => {
    const user = userEvent.setup();
    renderImport();

    await pickAndUploadPdf(user);

    await waitFor(() => expect(importApi.stagePdf).toHaveBeenCalledTimes(1));
    // Third argument is the optional statement password -- undefined here, since the field was
    // left blank, so an unprotected PDF's request body is byte-for-byte what it always was.
    expect(importApi.stagePdf).toHaveBeenCalledWith(
      expect.objectContaining({ name: 'statement.pdf' }),
      expect.any(Function),
      undefined
    );
    expect(importApi.stageCsv).not.toHaveBeenCalled();
  });

  it('is case-insensitive about the extension (e.g. STATEMENT.PDF)', async () => {
    const user = userEvent.setup();
    renderImport();

    await pickAndUploadPdf(user, pdfFile('STATEMENT.PDF'));

    await waitFor(() => expect(importApi.stagePdf).toHaveBeenCalledTimes(1));
    expect(importApi.stageCsv).not.toHaveBeenCalled();
  });

  it('routes a .pdf file dropped onto the dropzone to the password panel, not straight to staging', async () => {
    const user = userEvent.setup();
    renderImport();

    fireEvent.drop(screen.getByTestId('statement-dropzone'), { dataTransfer: { files: [pdfFile()] } });

    // Drag-and-drop and the file picker have to reach the same place -- a dropped PDF that
    // skipped the panel would silently lose the ability to carry a password.
    expect(await screen.findByTestId('pdf-password-panel')).toBeInTheDocument();
    expect(importApi.stagePdf).not.toHaveBeenCalled();

    await user.click(screen.getByRole('button', { name: /upload statement/i }));

    await waitFor(() => expect(importApi.stagePdf).toHaveBeenCalledTimes(1));
    expect(importApi.stageCsv).not.toHaveBeenCalled();
  });

  it('uploads a .csv immediately, with no password step in the way', async () => {
    const user = userEvent.setup();
    renderImport();

    await user.upload(screen.getByTestId('statement-file-input'), csvFile());

    // The panel is deliberately PDF-only: a CSV has nothing to unlock, so making every CSV
    // upload a two-step action would be pure cost.
    await waitFor(() => expect(importApi.stageCsv).toHaveBeenCalledTimes(1));
    expect(screen.queryByTestId('pdf-password-panel')).not.toBeInTheDocument();
  });

  it('rejects an unsupported file type dropped onto the dropzone, without calling either staging endpoint', async () => {
    renderImport();

    // Deliberately a fireEvent.drop, not userEvent.upload on the <input> -- upload() respects the
    // input's accept=".csv,.pdf" and silently won't apply a mismatched file (correctly modeling
    // how a real OS file picker filters selectable files), so it can never reach this guard
    // clause. Drag-and-drop is the one path that bypasses accept entirely in real browsers -- a
    // user can drop any file type regardless of it -- which is exactly the scenario this guard
    // exists to catch.
    const notAStatement = new File(['hello'], 'notes.txt', { type: 'text/plain' });
    fireEvent.drop(screen.getByTestId('statement-dropzone'), { dataTransfer: { files: [notAStatement] } });

    expect(await screen.findByText(/please upload a \.csv or \.pdf/i)).toBeInTheDocument();
    expect(importApi.stageCsv).not.toHaveBeenCalled();
    expect(importApi.stagePdf).not.toHaveBeenCalled();
  });

  it('advances to the review step once staging succeeds', async () => {
    const user = userEvent.setup();
    renderImport();

    await pickAndUploadPdf(user);

    // Review step renders the "which account" card once staging resolves and rows/detected
    // account land in state -- a lightweight signal that handleFile() ran to completion rather
    // than stopping short after the stage call.
    expect(await screen.findByText(/which account is this statement for/i)).toBeInTheDocument();
  });

  it('surfaces a PDF-specific error message when the server rejects a PDF it actually received', async () => {
    // Shaped like a real axios error the server actually answered (e.res.response is populated,
    // just with no specific message) -- distinct from the network-failure test below, which has
    // no .response at all. Only a request that reached the server and was rejected should ever
    // show a parser-specific message.
    vi.mocked(importApi.stagePdf).mockReset().mockRejectedValue({ response: { data: {} } });
    const user = userEvent.setup();
    renderImport();

    await pickAndUploadPdf(user);

    expect(await screen.findByText(/could not parse this pdf/i)).toBeInTheDocument();
  });

  it('surfaces a transport-failure message, not a parse error, when the request never reaches the server', async () => {
    // No .response at all -- axios's shape for a request that never got an HTTP response back
    // (network down, DNS failure, timeout, or a CORS-blocked preflight, all indistinguishable to
    // JS). This must NOT be reported as "Could not parse this PDF" -- the parser was never
    // involved, and that message sends debugging in the wrong direction.
    vi.mocked(importApi.stagePdf).mockReset().mockRejectedValue(new Error('Network Error'));
    const user = userEvent.setup();
    renderImport();

    await pickAndUploadPdf(user);

    const banner = await screen.findByText(/unable to reach the import service/i);
    expect(screen.queryByText(/could not parse this pdf/i)).not.toBeInTheDocument();
    // Sprint 4 item 22: a network failure is definitionally not something re-checking the file
    // fixes, so this must render danger (red), never warning (amber) -- the one branch of the
    // catch block that never computes an ErrorCode-derived actionRequired value at all.
    expect(banner.closest('p')?.className).toContain('text-danger');
    expect(banner.closest('p')?.className).not.toContain('text-warning');
  });
});

describe('Import — discarding the current review to force a fresh parse', () => {
  beforeEach(() => {
    vi.mocked(importApi.stageCsv).mockReset().mockResolvedValue(stagingResultWith({ sessionId: 'sess-9' }));
    vi.mocked(categoriesApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(accountsApi.list).mockReset().mockResolvedValue([]);
  });

  it('discards the staged session and returns to the upload step, after confirmation', async () => {
    vi.mocked(importApi.discardSession).mockReset().mockResolvedValue(undefined as never);
    const user = userEvent.setup();
    renderImport();

    await user.upload(screen.getByTestId('statement-file-input'), csvFile());
    await screen.findByText(/which account is this statement for/i);

    await user.click(screen.getByRole('button', { name: /discard and start over/i }));
    expect(await screen.findByText('Discard this import and start over?')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Discard' }));

    expect(importApi.discardSession).toHaveBeenCalledWith('sess-9');
    expect(await screen.findByTestId('statement-dropzone')).toBeInTheDocument();
  });

  it('does not discard without confirmation, and stays on the review screen', async () => {
    vi.mocked(importApi.discardSession).mockReset();
    const user = userEvent.setup();
    renderImport();

    await user.upload(screen.getByTestId('statement-file-input'), csvFile());
    await screen.findByText(/which account is this statement for/i);

    await user.click(screen.getByRole('button', { name: /discard and start over/i }));
    await screen.findByText('Discard this import and start over?');
    await user.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(importApi.discardSession).not.toHaveBeenCalled();
    expect(screen.getByText(/which account is this statement for/i)).toBeInTheDocument();
  });

  it('stays on the review screen and shows an error if discarding fails', async () => {
    vi.mocked(importApi.discardSession).mockReset().mockRejectedValue(new Error('network'));
    const user = userEvent.setup();
    renderImport();

    await user.upload(screen.getByTestId('statement-file-input'), csvFile());
    await screen.findByText(/which account is this statement for/i);

    await user.click(screen.getByRole('button', { name: /discard and start over/i }));
    await screen.findByText('Discard this import and start over?');
    await user.click(screen.getByRole('button', { name: 'Discard' }));

    expect(await screen.findByText(/could not discard/i)).toBeInTheDocument();
    expect(screen.getByText(/which account is this statement for/i)).toBeInTheDocument();
  });

  /**
   * Gap found in a pre-commit review: the discard control was only added to the single-account
   * review screen. A composite statement (e.g. a bank combining a savings account and a
   * recurring-deposit schedule in one PDF -- exactly the shape that motivated part of this
   * investigation) lands on the multi-account review screen instead, which had no discard control
   * at all even though `sessionId` is populated there too and the backend fix protects it equally.
   */
  it('also discards a multi-account session and returns to the upload step', async () => {
    vi.mocked(importApi.stagePdf).mockReset().mockResolvedValue({
      sessionId: 'sess-multi-9',
      multiAccount: true,
      staging: null,
      sections: [
        { detectedAccount, rows: [], totalParsed: 0, flaggedDuplicates: 0, unparseableRows: [] },
        { detectedAccount, rows: [], totalParsed: 0, flaggedDuplicates: 0, unparseableRows: [] },
      ],
    } as never);
    vi.mocked(importApi.discardSession).mockReset().mockResolvedValue(undefined as never);
    const user = userEvent.setup();
    renderImport();

    await pickAndUploadPdf(user);
    await screen.findByText(/this statement covers 2 accounts/i);

    await user.click(screen.getByRole('button', { name: /discard and start over/i }));
    expect(await screen.findByText('Discard this import and start over?')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Discard' }));

    expect(importApi.discardSession).toHaveBeenCalledWith('sess-multi-9');
    expect(await screen.findByTestId('statement-dropzone')).toBeInTheDocument();
  });
});

/**
 * Phase 1B: {@code totalAmountDue} was already correctly detected server-side but went no further
 * than the verification report -- this is the review screen's half of the plumbing fix.
 */
describe('Import — total amount due on the review screen', () => {
  beforeEach(() => {
    vi.mocked(categoriesApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(accountsApi.list).mockReset().mockResolvedValue([]);
  });

  it('shows the detected total amount due for a credit-card statement', async () => {
    vi.mocked(importApi.stagePdf).mockReset().mockResolvedValue({
      sessionId: 'session-1', multiAccount: false, sections: null,
      staging: {
        rows: [], totalParsed: 0, flaggedDuplicates: 0, unparseableRows: [],
        detectedAccount: { ...detectedAccount, suggestedAccountType: 'CREDIT_CARD', totalAmountDue: 27665.16 },
      },
    });
    const user = userEvent.setup();
    renderImport();

    await pickAndUploadPdf(user);

    expect(await screen.findByText(/total amount due \(detected\)/i)).toBeInTheDocument();
    expect(screen.getByText('₹27,665')).toBeInTheDocument();
  });

  it('does not show a total amount due row for a savings statement', async () => {
    vi.mocked(importApi.stagePdf).mockReset().mockResolvedValue({
      sessionId: 'session-1', multiAccount: false, sections: null,
      staging: {
        rows: [], totalParsed: 0, flaggedDuplicates: 0, unparseableRows: [],
        detectedAccount: { ...detectedAccount, suggestedAccountType: 'SAVINGS', totalAmountDue: null },
      },
    });
    const user = userEvent.setup();
    renderImport();

    await pickAndUploadPdf(user);

    expect(await screen.findByText(/which account is this statement for/i)).toBeInTheDocument();
    expect(screen.queryByText(/total amount due \(detected\)/i)).not.toBeInTheDocument();
  });
});

describe('Import — detected merchant on the review screen', () => {
  beforeEach(() => {
    vi.mocked(categoriesApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(accountsApi.list).mockReset().mockResolvedValue([]);
  });

  it('shows the detected merchant name under the raw description', async () => {
    vi.mocked(importApi.stagePdf).mockReset().mockResolvedValue({
      sessionId: 'session-1', multiAccount: false, sections: null,
      staging: {
        rows: [{
          date: '2026-07-10', description: 'UPI-SWIGGY-12345', amount: 350, type: 'EXPENSE',
          suggestedCategory: 'Food', categorySource: 'learned', ruleId: null, likelyDuplicate: false,
          referenceNumber: null, balanceAfter: null, duplicateMatch: null,
          merchant: 'SWIGGY', merchantConfidence: 1.0,
        }],
        totalParsed: 1, flaggedDuplicates: 0, unparseableRows: [], detectedAccount,
      },
    } as never);
    const user = userEvent.setup();
    renderImport();

    await pickAndUploadPdf(user);

    expect(await screen.findByText('UPI-SWIGGY-12345')).toBeInTheDocument();
    expect(screen.getByText('Detected: SWIGGY')).toBeInTheDocument();
  });

  it('shows nothing extra when no merchant was resolved', async () => {
    vi.mocked(importApi.stagePdf).mockReset().mockResolvedValue({
      sessionId: 'session-1', multiAccount: false, sections: null,
      staging: {
        rows: [{
          date: '2026-07-10', description: 'SOME BRAND NEW SHOP', amount: 350, type: 'EXPENSE',
          suggestedCategory: 'Other', categorySource: 'default', ruleId: null, likelyDuplicate: false,
          referenceNumber: null, balanceAfter: null, duplicateMatch: null,
          merchant: null, merchantConfidence: null,
        }],
        totalParsed: 1, flaggedDuplicates: 0, unparseableRows: [], detectedAccount,
      },
    } as never);
    const user = userEvent.setup();
    renderImport();

    await pickAndUploadPdf(user);

    expect(await screen.findByText('SOME BRAND NEW SHOP')).toBeInTheDocument();
    expect(screen.queryByText(/^Detected:/)).not.toBeInTheDocument();
  });
});

/**
 * Premium Import Reliability v1, Sprint 1 item 1: the failure UX contract. Fynora's own curated
 * copy, not the server's `message`, is what a user reads for a code the contract owns -- see
 * importFailureMessages.ts. A code the contract does NOT own falls through to exactly today's
 * behaviour, unchanged (covered by the two generic-fallback tests above, not repeated here).
 */
describe('Import — failure UX contract', () => {
  function rejectWithCode(errorCode: string, userActionRequired = false) {
    // The server message is deliberately different from the contract copy in every case below --
    // if a test passed while actually showing this string, it would mean the contract lookup was
    // never consulted, not that the two happened to agree. userActionRequired defaults to false
    // (the safe default a codeless failure, or a test that doesn't care, would get) -- callers that
    // assert on banner color pass the real value the backend would send for that specific code.
    return {
      response: { data: { errorCode, message: 'server-only wording that must not appear', userActionRequired } },
    };
  }

  it.each([
    ['no transaction table found', NO_HEADER_DETECTED],
    ['a table was found but nothing staged', NO_TRANSACTIONS_FOUND],
    ['a scanned/image-only PDF', SCANNED_OCR_REQUIRED],
    ['a corrupt/truncated PDF', CORRUPT_PDF],
  ])('shows the contract message, not the server message, for %s', async (_label, code) => {
    vi.mocked(importApi.stagePdf).mockReset().mockRejectedValue(rejectWithCode(code));
    const user = userEvent.setup();
    renderImport();

    await pickAndUploadPdf(user);

    expect(await screen.findByText(IMPORT_FAILURE_MESSAGES[code])).toBeInTheDocument();
    expect(screen.queryByText(/server-only wording/i)).not.toBeInTheDocument();
  });

  /**
   * Sprint 4 item 22. The banner's color, not just its text, must track whether the code is one
   * the user can fix themselves -- the async path (ImportTimeline) gets this straight from the
   * wire as `userStatus`; the sync path has no ImportJob to compute one, so it derives the same
   * answer from the identical per-code table the message itself came from.
   */
  it('colors the banner warning for an ACTION_REQUIRED code and danger for a plain FAILED one', async () => {
    vi.mocked(importApi.stagePdf).mockReset().mockRejectedValue(rejectWithCode(NO_HEADER_DETECTED, true));
    const user = userEvent.setup();
    renderImport();
    await pickAndUploadPdf(user);

    const actionRequiredBanner = (await screen.findByText(
      IMPORT_FAILURE_MESSAGES[NO_HEADER_DETECTED]
    )).closest('p');
    expect(actionRequiredBanner?.className).toContain('text-warning');
    expect(actionRequiredBanner?.className).not.toContain('text-danger');
  });

  it('colors the banner danger, not warning, for CORRUPT_PDF', async () => {
    vi.mocked(importApi.stagePdf).mockReset().mockRejectedValue(rejectWithCode(CORRUPT_PDF, false));
    const user = userEvent.setup();
    renderImport();
    await pickAndUploadPdf(user);

    const failedBanner = (await screen.findByText(
      IMPORT_FAILURE_MESSAGES[CORRUPT_PDF]
    )).closest('p');
    expect(failedBanner?.className).toContain('text-danger');
    expect(failedBanner?.className).not.toContain('text-warning');
  });

  /**
   * A stale errorActionRequired flag from a PREVIOUS ACTION_REQUIRED failure must not leak into a
   * later, unrelated error -- the exact bug showError()/clearError() (routing every error-banner
   * change through one pair of functions) exists to make structurally impossible. Reproduced by
   * hitting an ACTION_REQUIRED failure, choosing a different file (the real, reachable path back
   * to the plain dropzone), then hitting a plain validation error on the same mounted page.
   */
  it('does not carry a stale ACTION_REQUIRED color into a later, unrelated error', async () => {
    vi.mocked(importApi.stagePdf).mockReset().mockRejectedValue(rejectWithCode(NO_HEADER_DETECTED, true));
    const user = userEvent.setup();
    renderImport();
    await pickAndUploadPdf(user);
    await screen.findByText(IMPORT_FAILURE_MESSAGES[NO_HEADER_DETECTED]);

    await user.click(screen.getByRole('button', { name: /choose a different file/i }));

    // A plain client-side validation error, unrelated to any ErrorCode -- a fireEvent.drop, not
    // userEvent.upload, since upload() respects the input's accept=".csv,.pdf" and silently won't
    // apply a mismatched file; drop bypasses that, same as the dedicated test for this guard above.
    const notAStatement = new File(['not a statement'], 'notes.txt', { type: 'text/plain' });
    fireEvent.drop(screen.getByTestId('statement-dropzone'), { dataTransfer: { files: [notAStatement] } });

    const validationBanner = (await screen.findByText(
      /please upload a \.csv or \.pdf/i
    )).closest('p');
    expect(validationBanner?.className).toContain('text-danger');
    expect(validationBanner?.className).not.toContain('text-warning');
  });

  it('falls back to the server message for a code the contract does not own', async () => {
    // A real, valid ErrorCode (account-not-found, IMPORT_005) that simply isn't part of this
    // narrow first cut of the contract -- the safe-fallback path, not an error condition.
    vi.mocked(importApi.stagePdf).mockReset().mockRejectedValue(rejectWithCode('IMPORT_005'));
    const user = userEvent.setup();
    renderImport();

    await pickAndUploadPdf(user);

    expect(await screen.findByText(/server-only wording/i)).toBeInTheDocument();
  });
});

/**
 * Password-protected PDFs. Most Indian banks e-mail statements protected, so this is the normal
 * path for a large share of users rather than an edge case.
 */
describe('Import — password-protected PDFs', () => {
  // The REAL codes, imported rather than retyped -- errorCodes.ts is deliberately outside the
  // mocked endpoints module so these tests can't pass against a value the app no longer sends.
  function rejectWith(errorCode: string) {
    return { response: { data: { errorCode, message: 'server copy' } } };
  }

  beforeEach(() => {
    vi.mocked(importApi.stageCsv).mockReset().mockResolvedValue(stagingResultWith());
    vi.mocked(importApi.stagePdf).mockReset().mockResolvedValue(stagingResultWith());
    vi.mocked(categoriesApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(accountsApi.list).mockReset().mockResolvedValue([]);
  });

  it('sends a typed password along with the file', async () => {
    const user = userEvent.setup();
    renderImport();

    await pickAndUploadPdf(user, pdfFile(), 'AAAA1234');

    await waitFor(() => expect(importApi.stagePdf).toHaveBeenCalledTimes(1));
    expect(importApi.stagePdf).toHaveBeenCalledWith(expect.anything(), expect.any(Function), 'AAAA1234');
  });

  it('asks for a password when the server reports the file is protected', async () => {
    vi.mocked(importApi.stagePdf).mockReset().mockRejectedValue(rejectWith(PDF_PASSWORD_REQUIRED));
    const user = userEvent.setup();
    renderImport();

    await pickAndUploadPdf(user);

    expect(await screen.findByText(/this statement is password protected/i)).toBeInTheDocument();
    // A locked file is not a broken file, and saying so would send the user off looking for a
    // different export instead of the password they already have in their inbox.
    expect(screen.queryByText(/could not parse this pdf/i)).not.toBeInTheDocument();
  });

  /**
   * Sprint 4 item 22. The password prompt is its own dedicated panel with its own copy -- it must
   * never ALSO trigger the generic page-level error banner (showError/errorActionRequired), which
   * would duplicate the guidance or, worse, could start rendering an unintended color if a future
   * edit moved this branch relative to clearError(). Checked via the raw server `message`
   * ("server copy"), which the generic banner would show verbatim if it rendered at all -- the
   * password panel's own copy is fixed UI text and never echoes that field, so its presence can
   * only mean the generic banner leaked. (Not asserted via a CSS-class check: the panel
   * legitimately uses text-danger itself, for PDF_PASSWORD_INVALID's own "wrong password" hint --
   * a class-based check would false-positive on that unrelated, correct usage.) Both codes
   * covered, since REQUIRED and INVALID are handled by separate branches in the same catch.
   */
  it.each([PDF_PASSWORD_REQUIRED, PDF_PASSWORD_INVALID])(
    'never shows the generic error banner for %s -- only the password panel',
    async (code) => {
      vi.mocked(importApi.stagePdf).mockReset().mockRejectedValue(rejectWith(code));
      const user = userEvent.setup();
      renderImport();

      await pickAndUploadPdf(user);
      await screen.findByTestId('pdf-password-panel');

      expect(screen.queryByText('server copy')).not.toBeInTheDocument();
    }
  );

  it('retries the same file, without re-picking it, once a password is supplied', async () => {
    vi.mocked(importApi.stagePdf).mockReset()
      .mockRejectedValueOnce(rejectWith(PDF_PASSWORD_REQUIRED))
      .mockResolvedValueOnce(stagingResultWith());
    const user = userEvent.setup();
    renderImport();

    await pickAndUploadPdf(user);
    await screen.findByText(/this statement is password protected/i);

    await user.type(screen.getByLabelText(/statement password/i), 'AAAA1234');
    await user.click(screen.getByRole('button', { name: /upload statement/i }));

    // The retry must carry the SAME File the user already chose -- the whole point of holding it
    // in state is that a protected statement doesn't cost them a second trip to the file picker.
    await waitFor(() => expect(importApi.stagePdf).toHaveBeenCalledTimes(2));
    expect(vi.mocked(importApi.stagePdf).mock.calls[1][0]).toBe(vi.mocked(importApi.stagePdf).mock.calls[0][0]);
    expect(vi.mocked(importApi.stagePdf).mock.calls[1][2]).toBe('AAAA1234');
    expect(await screen.findByText(/which account is this statement for/i)).toBeInTheDocument();
  });

  it('keeps a rejected password in the field so it can be corrected rather than retyped', async () => {
    vi.mocked(importApi.stagePdf).mockReset().mockRejectedValue(rejectWith(PDF_PASSWORD_INVALID));
    const user = userEvent.setup();
    renderImport();

    await pickAndUploadPdf(user, pdfFile(), 'WRONG999');

    expect(await screen.findByText(/didn't open this statement/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/statement password/i)).toHaveValue('WRONG999');
    // Wrong password and never-asked read differently on purpose -- see PDF_PASSWORD_INVALID.
    expect(screen.queryByText(/this statement is password protected/i)).not.toBeInTheDocument();
  });

  it('does not offer the password to a browser password manager', async () => {
    const user = userEvent.setup();
    renderImport();

    await user.upload(screen.getByTestId('statement-file-input'), pdfFile());

    // It's the bank's password for one document, not a Fynora credential -- saving it into the
    // user's vault alongside real logins would be wrong, and it changes every statement anyway.
    expect(screen.getByLabelText(/statement password/i)).toHaveAttribute('autocomplete', 'off');
  });
});

describe('Import — Financial Product Discovery on the review screen', () => {
  beforeEach(() => {
    vi.mocked(categoriesApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(accountsApi.list).mockReset().mockResolvedValue([]);
  });

  function stageWithProduct(overrides: Partial<DetectedAccountInfo>) {
    const detected = { ...detectedAccount, ...overrides };
    vi.mocked(importApi.stagePdf).mockReset().mockResolvedValue({
      sessionId: 'session-1',
      multiAccount: false,
      sections: null,
      staging: { rows: [], totalParsed: 0, flaggedDuplicates: 0, detectedAccount: detected, unparseableRows: [] },
    });
  }

  it('confirms a validated product in one line rather than interrogating the user', async () => {
    stageWithProduct({ detectedProduct: 'SAVINGS', productConfidence: 0.87, productNeedsReview: false });
    const user = userEvent.setup();
    renderImport();

    await pickAndUploadPdf(user);

    expect(await screen.findByText(/detected a/i)).toBeInTheDocument();
    expect(screen.getByText(/savings account/i)).toBeInTheDocument();
    expect(screen.queryByText(/couldn’t identify/i)).not.toBeInTheDocument();
  });

  it('asks the user to name an unidentified product instead of prefilling a guess', async () => {
    // The decision that matters: a wrong product writes wrong data into net worth silently, while
    // asking costs one dropdown.
    stageWithProduct({ detectedProduct: 'UNKNOWN', productConfidence: 0, productNeedsReview: true });
    const user = userEvent.setup();
    renderImport();

    await pickAndUploadPdf(user);

    expect(await screen.findByText(/couldn’t identify what kind it is/i)).toBeInTheDocument();
  });

  it('shows the evidence behind an unproven classification on demand', async () => {
    // Explainability is the point of the evidence engine -- a classification the user cannot
    // interrogate is one they can only accept or distrust wholesale.
    stageWithProduct({
      detectedProduct: 'FIXED_DEPOSIT',
      productConfidence: 0.5,
      productNeedsReview: true,
      productEvidence: ['POSITIVE: MATURITY_FIELD (1.0) -- observed in column headers'],
    });
    const user = userEvent.setup();
    renderImport();

    await pickAndUploadPdf(user);

    const why = await screen.findByRole('button', { name: /why\?/i });
    expect(screen.queryByText(/MATURITY_FIELD/)).not.toBeInTheDocument();

    await user.click(why);

    expect(screen.getByText(/MATURITY_FIELD/)).toBeInTheDocument();
    expect(why).toHaveAttribute('aria-expanded', 'true');
  });
});

/**
 * The gate (WI5). These are page-level on purpose: the component tests prove the review screen
 * renders and reports decisions, but the thing that actually protects the ledger is the Confirm
 * Import button refusing to fire, and that only exists once the two are wired together.
 */
describe('Import — duplicate review gates the import', () => {
  const duplicateMatch = {
    existingTransactionId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
    existingAccountId: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
    existingDate: '2026-07-10',
    existingDescription: 'SWIGGY ORDER 4471',
    existingAmount: 486,
    existingType: 'EXPENSE' as const,
    existingImportedAt: '2026-07-11T09:00:00Z',
    matchCount: 1,
    confidence: 'EXACT' as const,
    reason: 'Same date, amount and description as a transaction already in your ledger.',
  };

  function stagedRow(description: string, duplicate: boolean) {
    return {
      date: '2026-07-10',
      description,
      amount: 486,
      type: 'EXPENSE' as const,
      suggestedCategory: 'Dining',
      categorySource: 'rule' as const,
      ruleId: null,
      likelyDuplicate: duplicate,
      referenceNumber: null,
      balanceAfter: null,
      duplicateMatch: duplicate ? { ...duplicateMatch, existingDescription: description } : null,
    };
  }

  function stageRows(rows: ReturnType<typeof stagedRow>[]) {
    vi.mocked(importApi.stagePdf).mockReset().mockResolvedValue({
      sessionId: 'session-1',
      multiAccount: false,
      sections: null,
      staging: {
        rows,
        totalParsed: rows.length,
        flaggedDuplicates: rows.filter((r) => r.likelyDuplicate).length,
        detectedAccount,
        unparseableRows: [],
      },
    } as never);
  }

  beforeEach(() => {
    vi.mocked(categoriesApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(accountsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(importApi.confirm).mockReset().mockResolvedValue({
      imported: 1, skipped: 1, duplicatesDetected: 1, transfersIdentified: 0, newMerchantsLearned: 0,
      accountsCreated: [], productsCreated: {}, categoriesAssigned: {}, warnings: [], account: null,
      totalCredits: 0, totalDebits: 486, statementOpeningBalance: null, statementClosingBalance: null,
      statementPeriodStart: null, statementPeriodEnd: null, importDurationMs: 12, source: 'PDF',
    } as never);
  });

  const confirmButton = () => screen.getByRole('button', { name: /confirm import/i });

  it('leaves the import blocked while a duplicate is undecided', async () => {
    stageRows([stagedRow('SWIGGY ORDER 4471', true)]);
    const user = userEvent.setup();
    renderImport();

    await pickAndUploadPdf(user);

    expect(await screen.findByTestId('duplicate-review')).toBeInTheDocument();
    expect(confirmButton()).toBeDisabled();
  });

  it('releases the import once every duplicate has an answer', async () => {
    stageRows([stagedRow('SWIGGY ORDER 4471', true), stagedRow('UBER TRIP 8891', true)]);
    const user = userEvent.setup();
    renderImport();

    await pickAndUploadPdf(user);
    await screen.findByTestId('duplicate-review');

    await user.click(screen.getAllByRole('button', { name: 'Import anyway' })[0]);
    expect(confirmButton()).toBeDisabled();

    await user.click(screen.getAllByRole('button', { name: 'Skip this row' })[1]);
    expect(confirmButton()).toBeEnabled();
  });

  /** A statement with nothing suspicious in it must not pay for this feature. */
  it('does not gate an import with no duplicates in it', async () => {
    stageRows([stagedRow('BLINKIT GROCERIES 9982', false)]);
    const user = userEvent.setup();
    renderImport();

    await pickAndUploadPdf(user);

    await waitFor(() => expect(confirmButton()).toBeEnabled());
    expect(screen.queryByTestId('duplicate-review')).not.toBeInTheDocument();
  });

  /**
   * Phase 4a: `confirming` moved from the button's `disabled` prop into its `loading` prop (a
   * separate change from the validation-gate conditions, which stayed in `disabled`). Regression
   * test for that split -- without it, the button could stay clickable while confirmImport() is
   * still in flight.
   */
  it('disables Confirm Import and shows a spinner while the confirm request is in flight', async () => {
    stageRows([stagedRow('BLINKIT GROCERIES 9982', false)]);
    let resolveConfirm: (r: Awaited<ReturnType<typeof importApi.confirm>>) => void;
    vi.mocked(importApi.confirm).mockReset().mockReturnValue(
      new Promise((resolve) => { resolveConfirm = resolve; })
    );
    const user = userEvent.setup();
    renderImport();

    await pickAndUploadPdf(user);
    await waitFor(() => expect(confirmButton()).toBeEnabled());

    await user.click(confirmButton());
    expect(confirmButton()).toBeDisabled();

    resolveConfirm!({
      imported: 1, skipped: 0, duplicatesDetected: 0, transfersIdentified: 0, newMerchantsLearned: 0,
      accountsCreated: [], productsCreated: {}, categoriesAssigned: {}, warnings: [], account: null,
      totalCredits: 0, totalDebits: 486, statementOpeningBalance: null, statementClosingBalance: null,
      statementPeriodStart: null, statementPeriodEnd: null, importDurationMs: 12, source: 'PDF',
    } as never);
    expect(await screen.findByText(/import complete/i)).toBeInTheDocument();
  });

  /**
   * The decision has to reach the payload, not just the button. "Skip" that still imports the row
   * would be worse than the silent filter it replaced, because the user was told it was handled.
   */
  it('sends only the rows the user chose to import', async () => {
    stageRows([stagedRow('SWIGGY ORDER 4471', true), stagedRow('UBER TRIP 8891', true)]);
    const user = userEvent.setup();
    renderImport();

    await pickAndUploadPdf(user);
    await screen.findByTestId('duplicate-review');

    await user.click(screen.getAllByRole('button', { name: 'Import anyway' })[0]);
    await user.click(screen.getAllByRole('button', { name: 'Skip this row' })[1]);
    await user.click(confirmButton());

    await waitFor(() => expect(importApi.confirm).toHaveBeenCalled());
    // The payload carries every parsed row with an include flag -- the backend is what filters --
    // so the decision is visible here as include, not as a shorter list.
    const payload = vi.mocked(importApi.confirm).mock.calls[0][0] as {
      rows: { description: string; include: boolean }[];
    };
    expect(payload.rows.map((r) => [r.description, r.include])).toEqual([
      ['SWIGGY ORDER 4471', true],
      ['UBER TRIP 8891', false],
    ]);
  });

  /**
   * The decision has to reach the SERVER, not just the payload's include flag. Reconciliation runs
   * straight after the import, sees two rows with the same date, amount and description, and — told
   * nothing — marks the later one as a duplicate, which strips it from every spend total. The user's
   * answer would show in the ledger and vanish from the numbers.
   */
  it('tells the server which duplicates the user personally cleared', async () => {
    stageRows([
      stagedRow('METRO FARE', true),
      stagedRow('SWIGGY ORDER 4471', true),
      stagedRow('BLINKIT GROCERIES 9982', false),
    ]);
    const user = userEvent.setup();
    renderImport();

    await pickAndUploadPdf(user);
    await screen.findByTestId('duplicate-review');

    await user.click(screen.getAllByRole('button', { name: 'Import anyway' })[0]);
    await user.click(screen.getAllByRole('button', { name: 'Skip this row' })[1]);
    await user.click(confirmButton());

    await waitFor(() => expect(importApi.confirm).toHaveBeenCalled());
    const payload = vi.mocked(importApi.confirm).mock.calls[0][0] as {
      rows: { description: string; confirmedNotDuplicate?: boolean }[];
    };
    expect(payload.rows.map((r) => [r.description, r.confirmedNotDuplicate === true])).toEqual([
      ['METRO FARE', true],
      ['SWIGGY ORDER 4471', false],
      // Never flagged, so there was no question to answer -- asserting "not a duplicate" about a
      // row nothing questioned would be claiming a decision the user was never asked to make.
      ['BLINKIT GROCERIES 9982', false],
    ]);
  });

  /** Bulk resolution is the difference between reviewing 3 duplicates and abandoning 40 of them,
   *  but it must never overwrite a row the user already answered by hand. */
  it('applies one decision to identical duplicates without touching answered ones', async () => {
    stageRows([
      stagedRow('METRO FARE', true),
      stagedRow('METRO FARE', true),
      stagedRow('METRO FARE', true),
    ]);
    const user = userEvent.setup();
    renderImport();

    await pickAndUploadPdf(user);
    await screen.findByTestId('duplicate-review');

    // Answer the last one by hand first, then bulk-resolve from the first.
    await user.click(screen.getAllByRole('button', { name: 'Skip this row' })[2]);
    await user.click(screen.getAllByRole('button', { name: 'Import anyway' })[0]);
    // Scoped to the first row: row 2 also offers a bulk action now, and clicking the wrong one
    // would prove nothing about which decision propagated.
    await user.click(
      within(screen.getByTestId('duplicate-0')).getByRole('button', { name: /Apply to 1 similar/ })
    );

    expect(confirmButton()).toBeEnabled();
    await user.click(confirmButton());

    await waitFor(() => expect(importApi.confirm).toHaveBeenCalled());
    // Rows 0 and 1 import (one by hand, one in bulk); row 2's earlier hand-made Skip survives.
    const payload = vi.mocked(importApi.confirm).mock.calls[0][0] as { rows: { include: boolean }[] };
    expect(payload.rows.map((r) => r.include)).toEqual([true, true, false]);
  });
});

/**
 * Milestone 2, item 4 — multi-account statements reach parity.
 *
 * A PDF that describes more than one account (an HSBC-style composite statement bundling a savings
 * account and a credit card) took a different route through this page: per-account review state,
 * no duplicate review, and `included: rows.map(r => !r.likelyDuplicate)` — the silent filter WI5
 * removed everywhere else. The row vanished from the import unless the user noticed a checkbox they
 * had never touched, on the one screen where they are also being asked to decide which account each
 * section belongs to.
 *
 * The acceptance test for the milestone is one sentence: **no path silently unticks a row.** These
 * are page-level because that is where it is true or false — the state machine's own tests
 * (lib/importReview.test.ts) prove the invariant, and these prove this screen is wired to it.
 */
describe('Import — multi-account statements get the same duplicate review', () => {
  const duplicateMatch = {
    existingTransactionId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
    existingAccountId: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
    existingDate: '2026-07-10',
    existingDescription: 'METRO FARE',
    existingAmount: 45,
    existingType: 'EXPENSE' as const,
    existingImportedAt: '2026-07-11T09:00:00Z',
    matchCount: 1,
    confidence: 'EXACT' as const,
    reason: 'Same date, amount and description as a transaction already in your ledger.',
  };

  function stagedRow(description: string, duplicate: boolean) {
    return {
      date: '2026-07-10',
      description,
      amount: 45,
      type: 'EXPENSE' as const,
      suggestedCategory: 'Transport',
      categorySource: 'rule' as const,
      ruleId: null,
      likelyDuplicate: duplicate,
      referenceNumber: null,
      balanceAfter: null,
      duplicateMatch: duplicate ? { ...duplicateMatch, existingDescription: description } : null,
    };
  }

  function section(
    name: string,
    rows: ReturnType<typeof stagedRow>[],
    detectedOverrides: Partial<DetectedAccountInfo> = {},
  ): StagedAccountSection {
    return {
      detectedAccount: { ...detectedAccount, suggestedName: name, ...detectedOverrides },
      rows,
      totalParsed: rows.length,
      flaggedDuplicates: rows.filter((r) => r.likelyDuplicate).length,
      unparseableRows: [],
    } as unknown as StagedAccountSection;
  }

  /** Stages a composite statement: `sections` is what the backend returns instead of `staging`
   *  once PdfPreviewGenerator detects more than one account section in one file. */
  function stageSections(sections: StagedAccountSection[]) {
    vi.mocked(importApi.stagePdf).mockReset().mockResolvedValue({
      sessionId: 'session-multi-1',
      multiAccount: true,
      staging: null,
      sections,
    } as never);
  }

  const savingsAndCard = () => [
    section('HSBC Savings', [stagedRow('METRO FARE', true), stagedRow('BLINKIT GROCERIES 9982', false)]),
    section('HSBC Credit Card', [stagedRow('SWIGGY ORDER 4471', true)]),
  ];

  beforeEach(() => {
    vi.mocked(categoriesApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(accountsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(importApi.confirmMulti).mockReset().mockResolvedValue({ perAccount: [] } as never);
  });

  const confirmAll = () => screen.getByRole('button', { name: /confirm all 2 accounts/i });
  const card = (i: number) => screen.getByTestId(`account-section-${i}`);

  /**
   * The acceptance criterion, asserted directly against the rendered screen: a row that arrives
   * unticked is a row the user is being shown and asked about. Before this, the first assertion
   * held and the second did not — the box was clear and there was no question anywhere.
   */
  it('never unticks a row without putting the question in front of the user', async () => {
    stageSections(savingsAndCard());
    const user = userEvent.setup();
    renderImport();

    await pickAndUploadPdf(user);
    await screen.findByText(/this statement covers 2 accounts/i);

    // Savings: the flagged fare is off, the clean grocery row is on.
    expect(within(card(0)).getByLabelText('Include METRO FARE')).not.toBeChecked();
    expect(within(card(0)).getByLabelText('Include BLINKIT GROCERIES 9982')).toBeChecked();

    // ...and the untick is a question, not a decision, in BOTH sections.
    expect(within(card(0)).getByTestId('duplicate-review')).toHaveTextContent('METRO FARE');
    expect(within(card(0)).getByTestId('duplicate-0')).toHaveTextContent('Needs a decision');
    expect(within(card(1)).getByTestId('duplicate-review')).toHaveTextContent('SWIGGY ORDER 4471');
    expect(within(card(1)).getByTestId('duplicate-0')).toHaveTextContent('Needs a decision');
  });

  it('blocks the whole import until every section has answered, and says which one is blocking', async () => {
    stageSections(savingsAndCard());
    const user = userEvent.setup();
    renderImport();

    await pickAndUploadPdf(user);
    await screen.findByText(/this statement covers 2 accounts/i);

    expect(confirmAll()).toBeDisabled();
    expect(screen.getByTestId('multi-duplicate-gate')).toHaveTextContent(/2 possible duplicates still need a decision/i);
    expect(screen.getByTestId('multi-duplicate-gate')).toHaveTextContent(/Account 1 of 2 and Account 2 of 2/);

    // Answering one section does not answer the other -- the review state is per account.
    await user.click(within(card(0)).getByRole('button', { name: 'Import anyway' }));
    expect(confirmAll()).toBeDisabled();
    expect(screen.getByTestId('multi-duplicate-gate')).toHaveTextContent(/1 possible duplicate still needs a decision/i);
    expect(screen.getByTestId('multi-duplicate-gate')).toHaveTextContent(/Account 2 of 2/);
    expect(screen.getByTestId('multi-duplicate-gate')).not.toHaveTextContent(/Account 1 of 2/);

    await user.click(within(card(1)).getByRole('button', { name: 'Skip this row' }));
    expect(confirmAll()).toBeEnabled();
    expect(screen.queryByTestId('multi-duplicate-gate')).not.toBeInTheDocument();
  });

  /** A composite statement with nothing suspicious in it must not pay for this feature. */
  it('does not gate a multi-account statement with no duplicates in it', async () => {
    stageSections([
      section('HSBC Savings', [stagedRow('BLINKIT GROCERIES 9982', false)]),
      section('HSBC Credit Card', [stagedRow('BLUE TOKAI COFFEE', false)]),
    ]);
    const user = userEvent.setup();
    renderImport();

    await pickAndUploadPdf(user);
    await screen.findByText(/this statement covers 2 accounts/i);

    await waitFor(() => expect(confirmAll()).toBeEnabled());
    expect(screen.queryByTestId('duplicate-review')).not.toBeInTheDocument();
  });

  /**
   * The decision has to reach the server per section, not just the button. This is the field the
   * multi-account confirm payload dropped entirely: without it the row lands in the ledger and
   * reconciliation immediately re-flags it, so the user's answer shows in the ledger and vanishes
   * from every spend total (commit 55f2db0, for the single-account path).
   */
  it('carries each section\'s decisions into that section\'s confirm payload', async () => {
    stageSections(savingsAndCard());
    const user = userEvent.setup();
    renderImport();

    await pickAndUploadPdf(user);
    await screen.findByText(/this statement covers 2 accounts/i);

    await user.click(within(card(0)).getByRole('button', { name: 'Import anyway' }));
    await user.click(within(card(1)).getByRole('button', { name: 'Skip this row' }));
    await user.click(confirmAll());

    await waitFor(() => expect(importApi.confirmMulti).toHaveBeenCalled());
    const payload = vi.mocked(importApi.confirmMulti).mock.calls[0][0] as {
      sections: { rows: { description: string; include: boolean; confirmedNotDuplicate?: boolean }[] }[];
    };

    expect(payload.sections).toHaveLength(2);
    expect(payload.sections[0].rows.map((r) => [r.description, r.include, r.confirmedNotDuplicate === true])).toEqual([
      ['METRO FARE', true, true],
      // Never flagged, so no decision to claim on its behalf.
      ['BLINKIT GROCERIES 9982', true, false],
    ]);
    expect(payload.sections[1].rows.map((r) => [r.description, r.include, r.confirmedNotDuplicate === true])).toEqual([
      ['SWIGGY ORDER 4471', false, false],
    ]);
  });

  /**
   * A composite statement's whole point is that one file holds two different KINDS of thing. A
   * savings section and a fixed-deposit section are not two accounts of the same shape, and the
   * fields that say so — `detectedProduct`, `productIdentityHash` and the seven deposit attributes —
   * were dropped between this screen and the request. The section was shown to the user as a fixed
   * deposit carrying a principal, a rate and a maturity date, and created as an empty savings
   * account.
   *
   * Asserted against the payload rather than the rendered card, because the screen was never the
   * broken half — it displayed every one of these correctly the whole time, which is exactly why
   * nobody saw it. The single-account path sent them from the beginning; only this one did not.
   */
  it('creates a deposit section as a deposit, carrying the numbers the screen showed', async () => {
    stageSections([
      section('HSBC Savings', [stagedRow('BLINKIT GROCERIES 9982', false)]),
      section('HSBC Fixed Deposit', [stagedRow('INTEREST CREDIT', false)], {
        suggestedAccountType: 'INVESTMENT',
        detectedProduct: 'FIXED_DEPOSIT',
        productNeedsReview: false,
        productIdentityHash: 'f1d2d2f924e986ac86fdf7b36c94bcdf32beec15',
        principalAmount: 250000,
        interestRate: 7.1,
        maturityDate: '2027-06-30',
        maturityAmount: 268400,
      }),
    ]);
    const user = userEvent.setup();
    renderImport();

    await pickAndUploadPdf(user);
    await screen.findByText(/this statement covers 2 accounts/i);
    await user.click(confirmAll());

    await waitFor(() => expect(importApi.confirmMulti).toHaveBeenCalled());
    const payload = vi.mocked(importApi.confirmMulti).mock.calls[0][0];

    // The savings section stays a savings account, and claims none of the deposit's numbers.
    expect(payload.sections[0].newAccount).toMatchObject({
      accountType: 'SAVINGS',
      detectedProduct: 'SAVINGS',
      principalAmount: null,
      maturityDate: null,
    });

    // The deposit section arrives as a deposit, with what makes it one.
    expect(payload.sections[1].newAccount).toMatchObject({
      accountType: 'INVESTMENT',
      detectedProduct: 'FIXED_DEPOSIT',
      productIdentityHash: 'f1d2d2f924e986ac86fdf7b36c94bcdf32beec15',
      principalAmount: 250000,
      interestRate: 7.1,
      maturityDate: '2027-06-30',
      maturityAmount: 268400,
    });
  });

  /**
   * The other half of the same rule, and the reason the fix is a shared builder rather than nine
   * more lines at this call site: a product the engine could not prove is the user's to name, and
   * the type they picked on the form is the answer. Echoing the guess back alongside their
   * correction would let the server prefer the guess.
   */
  it('does not assert a product the engine was unsure about', async () => {
    stageSections([
      section('HSBC Savings', [stagedRow('BLINKIT GROCERIES 9982', false)]),
      section('Unidentified section', [stagedRow('INTEREST CREDIT', false)], {
        detectedProduct: 'UNKNOWN',
        productNeedsReview: true,
        productConfidence: 0.2,
      }),
    ]);
    const user = userEvent.setup();
    renderImport();

    await pickAndUploadPdf(user);
    await screen.findByText(/this statement covers 2 accounts/i);
    await user.click(confirmAll());

    await waitFor(() => expect(importApi.confirmMulti).toHaveBeenCalled());
    const payload = vi.mocked(importApi.confirmMulti).mock.calls[0][0];

    expect(payload.sections[1].newAccount?.detectedProduct).toBeNull();
  });

  /**
   * "Apply to similar" is scoped to the section it was clicked in. Two accounts on one statement
   * routinely share a description (a card payment appears on both sides of a composite statement),
   * and each is a separate question against a separate ledger — resolving one from the other would
   * be a bulk action the user never aimed at that row.
   */
  it('keeps bulk resolution inside the account it was used in', async () => {
    stageSections([
      section('HSBC Savings', [stagedRow('METRO FARE', true), stagedRow('METRO FARE', true)]),
      section('HSBC Credit Card', [stagedRow('METRO FARE', true)]),
    ]);
    const user = userEvent.setup();
    renderImport();

    await pickAndUploadPdf(user);
    await screen.findByText(/this statement covers 2 accounts/i);

    await user.click(within(card(0)).getAllByRole('button', { name: 'Import anyway' })[0]);
    await user.click(within(card(0)).getByRole('button', { name: /Apply to 1 similar/ }));

    // Section 0 fully resolved; section 1's identical description untouched and still blocking.
    expect(within(card(0)).getByTestId('duplicate-review')).toHaveTextContent('All duplicates resolved.');
    expect(within(card(1)).getByTestId('duplicate-0')).toHaveTextContent('Needs a decision');
    expect(confirmAll()).toBeDisabled();

    await user.click(within(card(1)).getByRole('button', { name: 'Skip this row' }));
    await user.click(confirmAll());

    await waitFor(() => expect(importApi.confirmMulti).toHaveBeenCalled());
    const payload = vi.mocked(importApi.confirmMulti).mock.calls[0][0] as {
      sections: { rows: { include: boolean }[] }[];
    };
    expect(payload.sections[0].rows.map((r) => r.include)).toEqual([true, true]);
    expect(payload.sections[1].rows.map((r) => r.include)).toEqual([false]);
  });
});

/**
 * Phase 4 of the statement continuity proposal (§0.3/§0.23) extended to the multi-account summary
 * screen. Each account confirmed from a composite statement carries its own ImportSummary (one per
 * section), so each has to render its own warnings and its own "Import this one as a replacement?"
 * action independently -- a savings section duplicating an existing period says nothing about
 * whether the credit-card section in the same file does too.
 */
describe('Import — multi-account summary screen warnings', () => {
  function accountFor(name: string): Account {
    return {
      id: `acct-${name}`,
      name,
      accountType: 'SAVINGS',
      balance: 0,
      bank: detectedAccount.bank,
      lastImportedAt: null,
      lastStatementPeriodStart: null,
      lastStatementPeriodEnd: null,
      statementsCount: 1,
      transactionsCount: 1,
      status: 'ACTIVE',
    };
  }

  function summaryFor(name: string, overrides: Partial<{ warnings: string[]; duplicateOfStatementId: string | null; statementImportId: string }> = {}) {
    return {
      imported: 1, skipped: 0, duplicatesDetected: 0, transfersIdentified: 0, newMerchantsLearned: 0,
      accountsCreated: [], productsCreated: {}, categoriesAssigned: {},
      warnings: overrides.warnings ?? [],
      account: accountFor(name),
      totalCredits: 0, totalDebits: 0, statementOpeningBalance: null, statementClosingBalance: null,
      statementPeriodStart: null, statementPeriodEnd: null, importDurationMs: 12, source: 'PDF',
      statementImportId: overrides.statementImportId ?? `stmt-${name}`,
      duplicateOfStatementId: overrides.duplicateOfStatementId ?? null,
    };
  }

  function stagedRow(description: string) {
    return {
      date: '2026-07-10', description, amount: 45, type: 'EXPENSE' as const,
      suggestedCategory: 'Transport', categorySource: 'rule' as const, ruleId: null,
      likelyDuplicate: false, referenceNumber: null, balanceAfter: null, duplicateMatch: null,
    };
  }

  function section(name: string) {
    return {
      detectedAccount: { ...detectedAccount, suggestedName: name },
      rows: [stagedRow('BLINKIT GROCERIES 9982')],
      totalParsed: 1,
      flaggedDuplicates: 0,
      unparseableRows: [],
    } as unknown as StagedAccountSection;
  }

  function stageSections(sections: StagedAccountSection[]) {
    vi.mocked(importApi.stagePdf).mockReset().mockResolvedValue({
      sessionId: 'session-multi-1',
      multiAccount: true,
      staging: null,
      sections,
    } as never);
  }

  beforeEach(() => {
    vi.mocked(categoriesApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(accountsApi.list).mockReset().mockResolvedValue([]);
  });

  const confirmAll = () => screen.getByRole('button', { name: /confirm all 2 accounts/i });

  /** Nothing to pay for when neither section produced a warning. */
  it('renders no warnings block for an account with none, even when the other account has one', async () => {
    vi.mocked(importApi.confirmMulti).mockReset().mockResolvedValue({
      perAccount: [summaryFor('Savings'), summaryFor('Credit Card', { warnings: ['This statement has a gap before it.'] })],
    } as never);
    const user = userEvent.setup();
    renderImport();

    stageSections([section('Savings'), section('Credit Card')]);
    await pickAndUploadPdf(user);
    await screen.findByText(/this statement covers 2 accounts/i);
    await user.click(confirmAll());

    const savingsCard = await screen.findByTestId('summary-account-0');
    const cardCard = screen.getByTestId('summary-account-1');
    expect(within(savingsCard).queryByText(/gap before it/i)).not.toBeInTheDocument();
    expect(within(cardCard).getByText(/gap before it/i)).toBeInTheDocument();
  });

  /** The action only appears for the account whose own period actually duplicated one on file. */
  it('offers "Import this one as a replacement?" only for the account with a duplicateOfStatementId', async () => {
    vi.mocked(importApi.confirmMulti).mockReset().mockResolvedValue({
      perAccount: [
        summaryFor('Savings', { warnings: ['This period was already imported.'], duplicateOfStatementId: 'old-savings-stmt' }),
        summaryFor('Credit Card', { warnings: ['This period was already imported.'] }),
      ],
    } as never);
    const user = userEvent.setup();
    renderImport();

    stageSections([section('Savings'), section('Credit Card')]);
    await pickAndUploadPdf(user);
    await screen.findByText(/this statement covers 2 accounts/i);
    await user.click(confirmAll());

    const savingsCard = await screen.findByTestId('summary-account-0');
    const cardCard = screen.getByTestId('summary-account-1');
    expect(within(savingsCard).getByRole('button', { name: /import this one as a replacement/i })).toBeInTheDocument();
    expect(within(cardCard).queryByRole('button', { name: /import this one as a replacement/i })).not.toBeInTheDocument();
  });

  /**
   * Each card's supersede flow is independent local state -- confirming the replacement for one
   * account must not touch the other account's card, even though both are duplicates of some
   * existing statement.
   */
  it('supersedes independently per account, leaving the other account untouched', async () => {
    vi.mocked(importApi.confirmMulti).mockReset().mockResolvedValue({
      perAccount: [
        summaryFor('Savings', { warnings: ['This period was already imported.'], duplicateOfStatementId: 'old-savings-stmt', statementImportId: 'new-savings-stmt' }),
        summaryFor('Credit Card', { warnings: ['This period was already imported.'], duplicateOfStatementId: 'old-cc-stmt', statementImportId: 'new-cc-stmt' }),
      ],
    } as never);
    vi.mocked(statementImportsApi.supersede).mockReset().mockResolvedValue({ warning: null } as never);
    const user = userEvent.setup();
    renderImport();

    stageSections([section('Savings'), section('Credit Card')]);
    await pickAndUploadPdf(user);
    await screen.findByText(/this statement covers 2 accounts/i);
    await user.click(confirmAll());

    const savingsCard = await screen.findByTestId('summary-account-0');
    const cardCard = screen.getByTestId('summary-account-1');

    await user.click(within(savingsCard).getByRole('button', { name: /import this one as a replacement/i }));
    await user.click(screen.getByRole('button', { name: /^replace$/i }));

    await waitFor(() => expect(statementImportsApi.supersede).toHaveBeenCalledWith('old-savings-stmt', 'new-savings-stmt'));
    expect(statementImportsApi.supersede).toHaveBeenCalledTimes(1);
    expect(within(savingsCard).getByText(/existing statement has been replaced/i)).toBeInTheDocument();
    // The credit card card's own action is untouched -- still offering, not already replaced.
    expect(within(cardCard).getByRole('button', { name: /import this one as a replacement/i })).toBeInTheDocument();
    expect(within(cardCard).queryByText(/existing statement has been replaced/i)).not.toBeInTheDocument();
  });
});

/**
 * The queued path, from upload to review.
 *
 * <b>What this is really guarding.</b> The synchronous upload returns the staged rows in its own
 * response; the queued one returns a job id and nothing else, and the rows have to be fetched back
 * from the session the worker persisted. That handoff — job → session → review — is the whole
 * user-facing half of the durable queue, and every step of it is new. A worker that completed
 * without a session (which is what it used to do) would leave the user watching a bar that fills
 * and then goes nowhere.
 */
describe('Import — queued imports', () => {
  const queuedJob = (over: Partial<ImportJobProgress> = {}): ImportJobProgress => ({
    jobId: 'job-1',
    fileName: 'statement.csv',
    status: 'QUEUED',
    userStatus: 'PROCESSING',
    rowsTotal: null,
    rowsProcessed: 0,
    createdAt: '2026-08-08T09:00:00Z',
    startedAt: null,
    finishedAt: null,
    importSessionId: null,
    error: null,
    correlationId: null,
    ...over,
  });

  beforeEach(() => {
    vi.mocked(categoriesApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(accountsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(importApi.stageCsv).mockReset().mockResolvedValue(stagingResultWith());
    vi.mocked(importApi.getSession).mockReset();
    vi.mocked(importJobsApi.availability).mockReset().mockResolvedValue({ asyncImportAvailable: true });
    vi.mocked(importJobsApi.submit).mockReset().mockResolvedValue({
      jobId: 'job-1',
      statusUrl: '/api/v1/import/jobs/job-1',
    });
    // ImportTimeline mounts alongside ImportProgress for the life of every queued job and polls
    // this on the same real-timer schedule -- left unmocked, every test in this block leaves a
    // background retry loop running against an undefined response. A default empty timeline for
    // whatever the test doesn't care about keeps that loop harmless; tests about the timeline
    // itself override this.
    vi.mocked(importJobsApi.timeline).mockReset().mockResolvedValue({
      jobId: 'job-1', status: 'QUEUED', userStatus: 'PROCESSING', failureCode: null, stages: [],
    });
  });

  it('queues the upload instead of holding the request open', async () => {
    vi.mocked(importJobsApi.progress).mockResolvedValue(queuedJob({ status: 'PARSING' }));
    const user = userEvent.setup();
    renderImport();
    await waitFor(() => expect(importJobsApi.availability).toHaveBeenCalled());

    await user.upload(screen.getByTestId('statement-file-input'), csvFile());

    await waitFor(() => expect(importJobsApi.submit).toHaveBeenCalled());
    // Exactly one of the two paths runs. Both would upload the same file twice and stage it twice.
    expect(importApi.stageCsv).not.toHaveBeenCalled();
    expect(await screen.findByTestId('import-progress')).toBeInTheDocument();
    expect(await screen.findByText('Reading your statement')).toBeInTheDocument();
  });

  /**
   * StatementHistory's "Recent Imports" section (Premium Import Reliability v1, §3.2) reads this
   * exact key with a 30s staleTime. Without this invalidation, someone who visited that page
   * inside the last 30s, came here to submit a statement, then went straight back would see the
   * pre-submission snapshot instead of the job they just started.
   */
  it('invalidates the recent-imports list once a queued upload is accepted', async () => {
    vi.mocked(importJobsApi.progress).mockResolvedValue(queuedJob({ status: 'PARSING' }));
    const user = userEvent.setup();
    const { queryClient } = renderImport();
    await waitFor(() => expect(importJobsApi.availability).toHaveBeenCalled());
    queryClient.setQueryData(['import-jobs-recent'], []);

    await user.upload(screen.getByTestId('statement-file-input'), csvFile());

    await waitFor(() => expect(importJobsApi.submit).toHaveBeenCalled());
    expect(queryClient.getQueryState(['import-jobs-recent'])?.isInvalidated).toBe(true);
  });

  it('falls back to the synchronous upload where the queue is not available', async () => {
    // The queue is opt-in per deployment. Asked before the upload, so the file crosses the network
    // once rather than being sent, refused with a 503, and sent again.
    vi.mocked(importJobsApi.availability).mockResolvedValue({ asyncImportAvailable: false });
    const user = userEvent.setup();
    renderImport();
    await waitFor(() => expect(importJobsApi.availability).toHaveBeenCalled());

    await user.upload(screen.getByTestId('statement-file-input'), csvFile());

    await waitFor(() => expect(importApi.stageCsv).toHaveBeenCalled());
    expect(importJobsApi.submit).not.toHaveBeenCalled();
  });

  it('loads what the worker staged once the job completes', async () => {
    vi.mocked(importJobsApi.progress)
      .mockResolvedValueOnce(queuedJob({ status: 'ANALYZING', rowsTotal: 2, rowsProcessed: 1 }))
      .mockResolvedValue(queuedJob({
        status: 'COMPLETED', rowsTotal: 2, rowsProcessed: 2, importSessionId: 'session-9',
      }));
    vi.mocked(importApi.getSession).mockResolvedValue({
      sessionId: 'session-9',
      staging: { rows: [], totalParsed: 2, flaggedDuplicates: 0, detectedAccount, unparseableRows: [] },
    });
    const user = userEvent.setup();
    renderImport();
    await waitFor(() => expect(importJobsApi.availability).toHaveBeenCalled());

    await user.upload(screen.getByTestId('statement-file-input'), csvFile());

    // The handoff: the session the JOB named, fetched and opened for review.
    await waitFor(() => expect(importApi.getSession).toHaveBeenCalledWith('session-9'), { timeout: 4000 });
    // The progress panel gives way to the review step -- the same one the synchronous path reaches,
    // which is the point of the worker persisting a session rather than staging into a response.
    await waitFor(() => expect(screen.queryByTestId('import-progress')).not.toBeInTheDocument());
    expect(screen.getByText(/detected a/i)).toBeInTheDocument();
    expect(screen.queryByTestId('statement-file-input')).not.toBeInTheDocument();
  });

  /**
   * Bug fix, caught by a user report: a queued job's poller reaches COMPLETED and calls
   * getSession for the session it named -- but if that same session was already confirmed
   * through another path in the meantime (e.g. a duplicate confirm, or the user resuming it
   * from a second tab), the GET now 400s with IMPORT_SESSION_ALREADY_CONFIRMED. The bare catch
   * here used to show "Your statement was imported, but the review could not be loaded. Open it
   * from your unfinished imports" for every failure, including this one -- actively wrong twice
   * over: nothing is unloaded (the import already succeeded and is confirmed), and confirmed
   * sessions never appear in the unfinished-imports list, so the suggested next step is a dead
   * end. resumeSession (a few lines below) already distinguishes this exact error by code; this
   * mirrors that handling for the job-completion arrival path.
   */
  it('says the import was already reviewed, not a generic load failure, when a queued job\'s session was confirmed elsewhere first', async () => {
    vi.mocked(importJobsApi.progress).mockResolvedValue(queuedJob({
      status: 'COMPLETED', rowsTotal: 2, rowsProcessed: 2, importSessionId: 'session-already-confirmed',
    }));
    vi.mocked(importApi.getSession).mockRejectedValue({
      response: { data: { errorCode: IMPORT_SESSION_ALREADY_CONFIRMED, message: 'This import has already been reviewed and confirmed.' } },
    });
    const user = userEvent.setup();
    renderImport();
    await waitFor(() => expect(importJobsApi.availability).toHaveBeenCalled());

    await user.upload(screen.getByTestId('statement-file-input'), csvFile());

    expect(await screen.findByText(/already been reviewed and confirmed/i)).toBeInTheDocument();
    expect(screen.queryByText(/could not be loaded/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/unfinished imports/i)).not.toBeInTheDocument();
  });

  it('stops an import the user changed their mind about', async () => {
    vi.mocked(importJobsApi.progress).mockResolvedValue(queuedJob({ status: 'PARSING' }));
    vi.mocked(importJobsApi.cancel).mockResolvedValue(queuedJob({ status: 'CANCELLED' }));
    const user = userEvent.setup();
    renderImport();
    await waitFor(() => expect(importJobsApi.availability).toHaveBeenCalled());

    await user.upload(screen.getByTestId('statement-file-input'), csvFile());
    await screen.findByTestId('import-progress');

    // findBy, not getBy: the progress card renders as soon as the job id exists, but Cancel needs
    // the job's STATUS to know whether the server would still accept it, and that arrives with the
    // first poll at 100ms. The card used to poll at zero, so this was synchronously true.
    await user.click(await screen.findByRole('button', { name: 'Cancel' }));

    await waitFor(() => expect(importJobsApi.cancel).toHaveBeenCalledWith('job-1'));
    // Back to the dropzone, with no error: a cancel is the user's own decision and needs no
    // explanation shouted back at them.
    await waitFor(() => expect(screen.queryByTestId('import-progress')).not.toBeInTheDocument());
    expect(screen.getByTestId('statement-file-input')).toBeInTheDocument();
  });

  it('does not offer to cancel an import that is already writing to the ledger', async () => {
    // Mirrors the server's own boundary. A button the server would refuse reads as a bug.
    vi.mocked(importJobsApi.progress).mockResolvedValue(queuedJob({ status: 'IMPORTING', rowsTotal: 9 }));
    const user = userEvent.setup();
    renderImport();
    await waitFor(() => expect(importJobsApi.availability).toHaveBeenCalled());

    await user.upload(screen.getByTestId('statement-file-input'), csvFile());
    await screen.findByTestId('import-progress');

    expect(await screen.findByText('Adding them to your account')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cancel' })).not.toBeInTheDocument();
  });

  /**
   * Bug fix, caught by a post-ship review: this test used to assert the OPPOSITE of what it now
   * asserts -- that ImportProgress showed the server's raw error text. It did, and once
   * ImportTimeline started rendering alongside it for the same FAILED job (this same item), that
   * raw text sat permanently next to ImportTimeline's curated reason, disagreeing with it. The raw
   * text was never fit for a customer to read to begin with (`ImportJob.lastError` is `ExceptionClass:
   * message`) -- ImportTimeline is now the only place a failure reason is shown at all.
   */
  it('does not show the raw server error once ImportTimeline owns the failure reason', async () => {
    vi.mocked(importJobsApi.progress).mockResolvedValue(queuedJob({
      status: 'FAILED', error: 'No transactions could be read from this statement.',
    }));
    const user = userEvent.setup();
    renderImport();
    await waitFor(() => expect(importJobsApi.availability).toHaveBeenCalled());

    await user.upload(screen.getByTestId('statement-file-input'), csvFile());

    await waitFor(() => expect(screen.getByText("Couldn't finish")).toBeInTheDocument());
    expect(screen.queryByText('No transactions could be read from this statement.'))
      .not.toBeInTheDocument();
  });

  /**
   * The integration gap a code review caught: `ImportProgress` no longer resets `jobId` for a
   * FAILED job on its own (Premium Import Reliability v1, §3.1) -- that responsibility moved to
   * `ImportTimeline`'s "Try a different file" button, and every test above this one mocks
   * `importJobsApi.timeline` only with a default empty response, which would never have caught a
   * regression in that handoff. This drives the real integration end to end: upload, fail, read
   * the curated reason, dismiss, and confirm the dropzone is actually reachable again -- not just
   * that `ImportTimeline` renders correctly in isolation (`ImportTimeline.test.tsx` already covers
   * that).
   */
  it('lets the user try a different file after a queued import fails', async () => {
    vi.mocked(importJobsApi.progress).mockResolvedValue(queuedJob({ status: 'FAILED', error: 'boom' }));
    vi.mocked(importJobsApi.timeline).mockResolvedValue({
      jobId: 'job-1',
      status: 'FAILED',
      // IMPORT_001/NO_HEADER_DETECTED is one of the five ACTION_REQUIRED codes (ErrorCode's own
      // table) -- matching that here, not plain FAILED, is what makes this fixture the real shape
      // a FAILED job carrying this exact failureCode actually has.
      userStatus: 'ACTION_REQUIRED',
      failureCode: 'IMPORT_001', // NO_HEADER_DETECTED
      stages: [
        { stage: 'PARSING', attempt: 1, outcome: 'FAILED', startedAt: '2026-08-08T09:00:00Z', endedAt: '2026-08-08T09:00:01Z', durationMs: 1000 },
      ],
    });
    const user = userEvent.setup();
    renderImport();
    await waitFor(() => expect(importJobsApi.availability).toHaveBeenCalled());

    await user.upload(screen.getByTestId('statement-file-input'), csvFile());

    // The curated reason, not the raw wire code -- and it must still be readable, i.e. the page
    // must not have already reset out from under it.
    expect(await screen.findByText(/couldn't find a transaction table/i)).toBeInTheDocument();
    expect(screen.getByTestId('import-progress')).toBeInTheDocument();

    await user.click(await screen.findByRole('button', { name: 'Try a different file' }));

    // Actually back to the dropzone -- not just that the button existed and was clickable.
    await waitFor(() => expect(screen.queryByTestId('import-progress')).not.toBeInTheDocument());
    expect(screen.getByTestId('statement-file-input')).toBeInTheDocument();
  });

  /**
   * The other half of the same review finding: `ImportStageRecorder` deliberately tolerates its
   * own write failing without breaking the import ("a measurement gap, not an outage"), so a
   * FAILED job can genuinely reach the client with an empty stage list. Before the fix,
   * `ImportTimeline` rendered nothing at all for that combination -- no curated reason, no dismiss
   * button -- stranding the user on the failed screen with no way back short of a reload.
   */
  it('still offers a way back when a failed job has no recorded stages', async () => {
    vi.mocked(importJobsApi.progress).mockResolvedValue(queuedJob({ status: 'FAILED', error: 'boom' }));
    vi.mocked(importJobsApi.timeline).mockResolvedValue({
      jobId: 'job-1', status: 'FAILED', userStatus: 'FAILED', failureCode: null, stages: [],
    });
    const user = userEvent.setup();
    renderImport();
    await waitFor(() => expect(importJobsApi.availability).toHaveBeenCalled());

    await user.upload(screen.getByTestId('statement-file-input'), csvFile());

    await user.click(await screen.findByRole('button', { name: 'Try a different file' }));

    await waitFor(() => expect(screen.queryByTestId('import-progress')).not.toBeInTheDocument());
    expect(screen.getByTestId('statement-file-input')).toBeInTheDocument();
  });
});

/**
 * "Continue previous import" -- Premium Import Reliability v1, §3. The backend already scopes
 * GET/DELETE /import/sessions to the caller's own, active sessions (ImportSessionService); this
 * suite is about whether the UI correctly lists what that endpoint returns, resumes into the same
 * review step the synchronous upload flow lands on, and discards with confirmation -- not about
 * re-proving ownership isolation the frontend has no way to violate (it never has another user's
 * session id to ask for).
 */
describe('Import — continuing an unfinished import', () => {
  function unfinishedSession(overrides: Partial<{ id: string; fileName: string; rowCount: number; createdAt: string; expiresAt: string }> = {}) {
    return {
      id: 'sess-1',
      fileName: 'hdfc-july.pdf',
      rowCount: 42,
      createdAt: '2026-08-12T10:00:00Z',
      expiresAt: '2026-08-14T10:00:00Z',
      ...overrides,
    };
  }

  beforeEach(() => {
    vi.mocked(categoriesApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(accountsApi.list).mockReset().mockResolvedValue([]);
    // Reset call history, not just behaviour -- these tests assert stageCsv/stagePdf were NEVER
    // called, which would be a false pass (or, as first written, a false failure) against
    // whatever call count an earlier describe block in this file left behind.
    vi.mocked(importApi.stageCsv).mockReset();
    vi.mocked(importApi.stagePdf).mockReset();
  });

  it('lists unfinished sessions and does not show the section when there are none', async () => {
    vi.mocked(importApi.listSessions).mockReset().mockResolvedValue([]);
    renderImport();

    // Give the query a chance to resolve before asserting absence, so this isn't just "the query
    // hasn't finished yet" passing for the wrong reason.
    await screen.findByTestId('statement-dropzone');
    expect(screen.queryByText('Continue previous import')).not.toBeInTheDocument();
  });

  it('resumes a session into the same review step the upload flow uses, with no re-upload', async () => {
    vi.mocked(importApi.listSessions).mockReset().mockResolvedValue([unfinishedSession()]);
    vi.mocked(importApi.getSession).mockReset().mockResolvedValue(stagingResultWith({ sessionId: 'sess-1' }));
    const user = userEvent.setup();
    renderImport();

    await screen.findByText('hdfc-july.pdf');
    await user.click(screen.getByRole('button', { name: /continue import/i }));

    expect(importApi.getSession).toHaveBeenCalledWith('sess-1');
    expect(await screen.findByText(/which account is this statement for/i)).toBeInTheDocument();
    // No staging call of any kind -- the whole point is that the bytes and rows are already
    // server-side from the original upload.
    expect(importApi.stageCsv).not.toHaveBeenCalled();
    expect(importApi.stagePdf).not.toHaveBeenCalled();
  });

  /**
   * Phase 4a (animation-polish roadmap): resumeSession() awaits importApi.getSession() before
   * setStep('review') ever fires, but the "Continue Import" button had no in-flight feedback at
   * all -- no spinner, no disabled state, nothing -- so clicking it looked like a dead button for
   * however long that fetch took. Regression test for the fix: a resumingSessionId state now
   * drives the button's `loading` prop.
   */
  it('disables Continue Import and shows a spinner while its own session fetch is in flight', async () => {
    vi.mocked(importApi.listSessions).mockReset().mockResolvedValue([unfinishedSession()]);
    let resolveGetSession: (r: ReturnType<typeof stagingResultWith>) => void;
    vi.mocked(importApi.getSession).mockReset().mockReturnValue(
      new Promise((resolve) => { resolveGetSession = resolve; })
    );
    const user = userEvent.setup();
    renderImport();

    await screen.findByText('hdfc-july.pdf');
    const continueButton = screen.getByRole('button', { name: /continue import/i });
    expect(continueButton).not.toBeDisabled();

    await user.click(continueButton);
    expect(continueButton).toBeDisabled();

    resolveGetSession!(stagingResultWith({ sessionId: 'sess-1' }));
    await screen.findByText(/which account is this statement for/i);
  });

  /**
   * Regression test for a bug an adversarial review caught in the fix above: the first version
   * tracked "which session is resuming" as a single id, not a set. Every row's Continue Import
   * button is independently clickable with nothing gating a second click while the first row's
   * fetch is still in flight -- so clicking a second unfinished session's button silently cleared
   * the first row's spinner (the shared value got overwritten), and whichever fetch's `finally`
   * ran last wiped out the other's loading state even while its own request was still pending.
   */
  it('tracks two concurrent resumes independently, so neither row\'s spinner clears the other\'s', async () => {
    vi.mocked(importApi.listSessions).mockReset().mockResolvedValue([
      unfinishedSession({ id: 'sess-a', fileName: 'a.csv' }),
      unfinishedSession({ id: 'sess-b', fileName: 'b.csv' }),
    ]);
    let rejectA: (e: unknown) => void;
    let resolveB: (r: ReturnType<typeof stagingResultWith>) => void;
    vi.mocked(importApi.getSession).mockReset().mockImplementation((id: unknown) => {
      if (id === 'sess-a') return new Promise((_resolve, reject) => { rejectA = reject; });
      return new Promise((resolve) => { resolveB = resolve; });
    });
    const user = userEvent.setup();
    renderImport();

    await screen.findByText('a.csv');
    const [continueA, continueB] = screen.getAllByRole('button', { name: /continue import/i });

    await user.click(continueA);
    expect(continueA).toBeDisabled();
    expect(continueB).not.toBeDisabled();

    await user.click(continueB);
    // The bug: this click used to clear session A's loading state too.
    expect(continueA).toBeDisabled();
    expect(continueB).toBeDisabled();

    // A fails (kept as a rejection, not a resolve, so both rows stay mounted and B's state stays
    // observable -- a successful resume navigates the whole page to the review step).
    rejectA!(new Error('expired'));
    await screen.findByText(/no longer available/i);
    // The bug: A's finally used to unconditionally clear the shared id, wiping out B's spinner too
    // even though B's own fetch was still pending.
    expect(continueB).toBeDisabled();

    resolveB!(stagingResultWith({ sessionId: 'sess-b' }));
    await screen.findByText(/which account is this statement for/i);
  });

  it('shows a clear message and refreshes the list when a session expired before it was resumed', async () => {
    vi.mocked(importApi.listSessions).mockReset().mockResolvedValue([unfinishedSession()]);
    vi.mocked(importApi.getSession).mockReset().mockRejectedValue(new Error('expired'));
    const user = userEvent.setup();
    renderImport();

    await screen.findByText('hdfc-july.pdf');
    await user.click(screen.getByRole('button', { name: /continue import/i }));

    expect(await screen.findByText(/no longer available/i)).toBeInTheDocument();
    // Re-listed so a now-stale entry doesn't sit there as a button that will fail the same way
    // again -- called once on mount, once after the failed resume.
    await waitFor(() => expect(importApi.listSessions).toHaveBeenCalledTimes(2));
  });

  it('discards a session after confirmation and removes it from the list', async () => {
    vi.mocked(importApi.listSessions).mockReset()
      .mockResolvedValueOnce([unfinishedSession()])
      .mockResolvedValueOnce([]);
    vi.mocked(importApi.discardSession).mockReset().mockResolvedValue(undefined as never);
    const user = userEvent.setup();
    renderImport();

    await screen.findByText('hdfc-july.pdf');
    await user.click(screen.getByTitle('Discard Unfinished Import'));
    // Custom in-app confirmation (ConfirmDialog), not the browser's own confirm() -- see this
    // page's own doc comment on confirmDiscardId for why.
    expect(await screen.findByText('Discard this unfinished import?')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Discard' }));

    expect(importApi.discardSession).toHaveBeenCalledWith('sess-1');
    await waitFor(() => expect(screen.queryByText('hdfc-july.pdf')).not.toBeInTheDocument());
  });

  it('does not discard without confirmation', async () => {
    vi.mocked(importApi.listSessions).mockReset().mockResolvedValue([unfinishedSession()]);
    vi.mocked(importApi.discardSession).mockReset();
    const user = userEvent.setup();
    renderImport();

    await screen.findByText('hdfc-july.pdf');
    await user.click(screen.getByTitle('Discard Unfinished Import'));
    await screen.findByText('Discard this unfinished import?');
    await user.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(importApi.discardSession).not.toHaveBeenCalled();
    expect(screen.getByText('hdfc-july.pdf')).toBeInTheDocument();
    expect(screen.queryByText('Discard this unfinished import?')).not.toBeInTheDocument();
  });

  /**
   * Bug fix, caught by review while scoping Sprint 4 item 22: discardStagedSession() never
   * cleared the page-level error banner on success, unlike every other action here. Reproduced
   * directly rather than inferred -- an unrelated ACTION_REQUIRED failure (amber) must not sit on
   * screen, now misleadingly reading as still-relevant guidance, after a completely unrelated
   * staged session is discarded.
   */
  it('clears a stale error banner once an unrelated staged session is successfully discarded', async () => {
    vi.mocked(importApi.listSessions).mockReset()
      .mockResolvedValueOnce([unfinishedSession()])
      .mockResolvedValueOnce([]);
    vi.mocked(importApi.discardSession).mockReset().mockResolvedValue(undefined as never);
    // CSV, not PDF: a PDF failure leaves `pendingPdf` set, which hides the "Continue previous
    // import" / Discard section entirely (showUploadPicker requires !pendingPdf) -- not the
    // scenario this test needs. CSV uploads on selection with no intermediate panel, so the
    // dropzone (and the resume section beside it) is what's still showing when the error lands.
    vi.mocked(importApi.stageCsv).mockReset().mockRejectedValue({
      response: { data: { errorCode: NO_HEADER_DETECTED, message: 'server-only wording' } },
    });
    const user = userEvent.setup();
    renderImport();

    await screen.findByText('hdfc-july.pdf');
    await user.upload(screen.getByTestId('statement-file-input'), csvFile());
    await screen.findByText(IMPORT_FAILURE_MESSAGES[NO_HEADER_DETECTED]);

    await user.click(screen.getByTitle('Discard Unfinished Import'));
    await screen.findByText('Discard this unfinished import?');
    await user.click(screen.getByRole('button', { name: 'Discard' }));

    await waitFor(() =>
      expect(screen.queryByText(IMPORT_FAILURE_MESSAGES[NO_HEADER_DETECTED])).not.toBeInTheDocument()
    );
  });

  it('fails closed when the sessions list itself cannot be loaded -- the rest of the page still works', async () => {
    vi.mocked(importApi.listSessions).mockReset().mockRejectedValue(new Error('network error'));
    renderImport();

    expect(await screen.findByTestId('statement-dropzone')).toBeInTheDocument();
    expect(screen.queryByText('Continue previous import')).not.toBeInTheDocument();
  });
});

/**
 * The import detail page's "Review" action (Premium Import Reliability v1, §3.2) arrives here via
 * router state rather than a session id in the URL. This reuses the exact `resumeSession` function
 * the "continue previous import" section above already calls -- so the only thing worth testing at
 * this layer is that the state actually triggers it on mount, not the resume behaviour itself.
 */
describe('Import — resuming via navigation state', () => {
  function renderImportWithResumeState(resumeSessionId: string) {
    const queryClient = new QueryClient();
    return render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[{ pathname: '/app/import', state: { kind: 'resume', resumeSessionId } }]}>
          <AuthProvider>
            <Import />
          </AuthProvider>
        </MemoryRouter>
      </QueryClientProvider>
    );
  }

  function existingAccount(overrides: Partial<Account> = {}): Account {
    return {
      id: 'acct-existing-1',
      name: 'My Savings',
      accountType: 'SAVINGS',
      balance: 1000,
      accountHolderName: null,
      accountNumberMasked: null,
      branchName: null,
      ifscCode: null,
      bank: {
        id: 'OTHER', officialName: null, shortName: 'Other', colorHex: '#000000', initials: 'OT',
        logoPath: '', category: null, websiteUrl: null, ifscPrefix: null, supportedAccountTypes: [],
      },
      lastImportedAt: null,
      lastStatementPeriodStart: null,
      lastStatementPeriodEnd: null,
      statementsCount: 0,
      transactionsCount: 0,
      status: 'ACTIVE',
      ...overrides,
    };
  }

  beforeEach(() => {
    vi.mocked(importApi.listSessions).mockReset().mockResolvedValue([]);
    vi.mocked(importApi.getSession).mockReset();
    vi.mocked(accountsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(categoriesApi.list).mockReset().mockResolvedValue([]);
  });

  it('opens the review step for the session named in navigation state, with no re-upload', async () => {
    vi.mocked(importApi.getSession).mockResolvedValue(stagingResultWith({ sessionId: 'sess-from-detail' }));
    renderImportWithResumeState('sess-from-detail');

    expect(await screen.findByText(/which account is this statement for/i)).toBeInTheDocument();
    expect(importApi.getSession).toHaveBeenCalledWith('sess-from-detail');
    expect(importApi.stageCsv).not.toHaveBeenCalled();
    expect(importApi.stagePdf).not.toHaveBeenCalled();
  });

  /**
   * Bug fix, caught by a pre-ship review: auto-resuming used to call resumeSession from the mount
   * effect's own closure, which is fixed to that render's existingAccounts -- always `[]`, since
   * accountsApi.list() (fired in the very same effect) resolves later, on a render this closure
   * never sees. matchExistingAccount then always saw an empty list and always returned null, so
   * this screen silently defaulted to "create a new account" even for a statement that plainly
   * matched one the user already had -- the exact merge-risk the comment on hydrateReviewFrom's
   * caller (Import.tsx, near matchExistingAccount) exists to prevent, reintroduced through this
   * second entry point. This test only passes because the fix threads the freshly-resolved
   * accounts list straight into resumeSession/hydrateReviewFrom instead of relying on that state.
   */
  it('matches an existing account instead of silently defaulting to "create a new account"', async () => {
    vi.mocked(accountsApi.list).mockReset().mockResolvedValue([existingAccount()]);
    vi.mocked(importApi.getSession).mockResolvedValue(stagingResultWith({ sessionId: 'sess-from-detail' }));
    renderImportWithResumeState('sess-from-detail');

    await screen.findByText(/which account is this statement for/i);
    expect(screen.getByRole('radio', { name: /use an existing account/i })).toBeChecked();
  });

  it('shows the same expired-session message the list-driven resume uses', async () => {
    vi.mocked(importApi.getSession).mockRejectedValue(new Error('expired'));
    renderImportWithResumeState('sess-gone');

    expect(await screen.findByText(/no longer available/i)).toBeInTheDocument();
  });

  /**
   * Bug fix, caught by review: isReviewable(job) on ImportDetail.tsx never turns false once a job
   * completes with a session id, even after that session was already reviewed and confirmed
   * through the normal flow -- so "Review this import" can still be clicked for an import that
   * already succeeded. Before this fix, the bare catch below showed the SAME "may have expired,
   * please upload again" message for this case as for a genuinely expired one, which is actively
   * wrong: nothing needs re-uploading, the import is already done.
   */
  it('says the import was already reviewed, not that it expired, for an already-confirmed session', async () => {
    vi.mocked(importApi.getSession).mockRejectedValue({
      response: { data: { errorCode: IMPORT_SESSION_ALREADY_CONFIRMED, message: 'This import has already been reviewed and confirmed.' } },
    });
    renderImportWithResumeState('sess-already-confirmed');

    expect(await screen.findByText(/already been reviewed and confirmed/i)).toBeInTheDocument();
    expect(screen.queryByText(/no longer available/i)).not.toBeInTheDocument();
  });
});

/**
 * The Failed Imports section's "Try again" action (Premium Import Reliability v1, §2.5) arrives
 * here with no staged data at all -- a failed sync import has no bytes retained, so unlike
 * reimport/resume there is nothing to hydrate. This is purely a contextual banner on the ordinary
 * upload step, reminding the person which file and why it failed last time.
 */
describe('Import — arriving to retry a failed sync import', () => {
  function renderImportWithRetryState(retryFileName: string, retryFailureCode: string | null) {
    const queryClient = new QueryClient();
    return render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[{
          pathname: '/app/import',
          state: { kind: 'retry', retryFileName, retryFailureCode },
        }]}>
          <AuthProvider>
            <Import />
          </AuthProvider>
        </MemoryRouter>
      </QueryClientProvider>
    );
  }

  beforeEach(() => {
    vi.mocked(importApi.listSessions).mockReset().mockResolvedValue([]);
    vi.mocked(importApi.getSession).mockReset();
    vi.mocked(accountsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(categoriesApi.list).mockReset().mockResolvedValue([]);
  });

  it('shows which file and the curated reason for a known failure code, still on the ordinary upload step', async () => {
    renderImportWithRetryState('bad-statement.pdf', NO_HEADER_DETECTED);

    expect(await screen.findByTestId('statement-dropzone')).toBeInTheDocument();
    expect(screen.getByText('bad-statement.pdf')).toBeInTheDocument();
    expect(screen.getByText(new RegExp(IMPORT_FAILURE_MESSAGES[NO_HEADER_DETECTED]))).toBeInTheDocument();
  });

  /**
   * Bug fix, caught by review: startOver()'s stale-arrival-state fix only covered "finish an
   * import, click Import Another" -- but dismissing a FAILED queued import and giving up on a
   * CANCELLED one are two OTHER paths back to this same "nothing pending" upload step, and
   * neither used to clear location.state. Arriving via "Try again" for one file, then failing or
   * cancelling an unrelated SECOND upload through the async queue, used to leave the FIRST file's
   * stale "Retrying <file>" banner still showing under the second file's own outcome.
   */
  describe('a second, unrelated upload after arriving to retry', () => {
    const queuedJob = (over: Partial<ImportJobProgress> = {}): ImportJobProgress => ({
      jobId: 'job-2',
      fileName: 'unrelated-second-file.csv',
      status: 'QUEUED',
      userStatus: 'PROCESSING',
      rowsTotal: null,
      rowsProcessed: 0,
      createdAt: '2026-08-08T09:00:00Z',
      startedAt: null,
      finishedAt: null,
      importSessionId: null,
      error: null,
      correlationId: null,
      ...over,
    });

    beforeEach(() => {
      vi.mocked(importJobsApi.availability).mockReset().mockResolvedValue({ asyncImportAvailable: true });
      vi.mocked(importJobsApi.submit).mockReset().mockResolvedValue({
        jobId: 'job-2', statusUrl: '/api/v1/import/jobs/job-2',
      });
      vi.mocked(importJobsApi.progress).mockReset();
      vi.mocked(importJobsApi.cancel).mockReset();
      vi.mocked(importJobsApi.timeline).mockReset().mockResolvedValue({
        jobId: 'job-2', status: 'QUEUED', userStatus: 'PROCESSING', failureCode: null, stages: [],
      });
    });

    it('clears the retry banner once the second upload fails and is dismissed', async () => {
      vi.mocked(importJobsApi.progress).mockResolvedValue(queuedJob({ status: 'FAILED', error: 'boom' }));
      vi.mocked(importJobsApi.timeline).mockResolvedValue({
        jobId: 'job-2', status: 'FAILED', userStatus: 'FAILED', failureCode: null, stages: [],
      });
      const user = userEvent.setup();
      renderImportWithRetryState('bad-statement.pdf', NO_HEADER_DETECTED);

      await screen.findByTestId('retry-import-banner');
      await user.upload(screen.getByTestId('statement-file-input'), csvFile());
      await screen.findByTestId('import-timeline');
      await user.click(await screen.findByRole('button', { name: 'Try a different file' }));

      await waitFor(() => expect(screen.queryByTestId('import-progress')).not.toBeInTheDocument());
      // Bug fix (CI flake, same root cause as the sibling "cancelled" test below):
      // clearArrivalState()'s navigate() call -- which actually clears retryState -- lands on a
      // later render than the setJobId(null) that unmounts the timeline above, since router
      // navigation and local useState updates commit on separate ticks. Wrapped in the same
      // waitFor idiom as the line above so both are given a chance to actually settle.
      await waitFor(() => {
        expect(screen.queryByTestId('retry-import-banner')).not.toBeInTheDocument();
        expect(screen.queryByText('bad-statement.pdf')).not.toBeInTheDocument();
      });
    });

    it('clears the retry banner once the second upload is cancelled', async () => {
      vi.mocked(importJobsApi.progress).mockResolvedValue(queuedJob({ status: 'PARSING' }));
      vi.mocked(importJobsApi.cancel).mockResolvedValue(queuedJob({ status: 'CANCELLED' }));
      const user = userEvent.setup();
      renderImportWithRetryState('bad-statement.pdf', NO_HEADER_DETECTED);

      await screen.findByTestId('retry-import-banner');
      await user.upload(screen.getByTestId('statement-file-input'), csvFile());
      await screen.findByTestId('import-progress');
      await user.click(await screen.findByRole('button', { name: 'Cancel' }));

      await waitFor(() => expect(screen.queryByTestId('import-progress')).not.toBeInTheDocument());
      // Bug fix (CI flake): clearArrivalState()'s navigate() call -- which is what actually
      // clears retryState -- lands on a later render than the setJobId(null) that unmounts
      // ImportProgress above, since router navigation and local useState updates commit on
      // separate ticks. A bare synchronous expect() here raced that second update and failed
      // intermittently under CI's timing (passed locally, failed in CI); wrapping in the same
      // waitFor idiom as the line above waits for both to actually settle.
      await waitFor(() => {
        expect(screen.queryByTestId('retry-import-banner')).not.toBeInTheDocument();
        expect(screen.queryByText('bad-statement.pdf')).not.toBeInTheDocument();
      });
    });
  });

  it('falls back to a safe generic message for an unmapped or missing failure code', async () => {
    renderImportWithRetryState('bad-statement.pdf', null);

    await screen.findByTestId('statement-dropzone');
    expect(screen.getByText(/Fynora couldn't complete this import\./)).toBeInTheDocument();
  });

  it('does not stage or resume anything -- the person must still pick the file', async () => {
    renderImportWithRetryState('bad-statement.pdf', null);

    await screen.findByTestId('statement-dropzone');
    expect(importApi.stageCsv).not.toHaveBeenCalled();
    expect(importApi.stagePdf).not.toHaveBeenCalled();
    expect(importApi.getSession).not.toHaveBeenCalled();
  });

  it('shows no retry banner on an ordinary visit with no navigation state', async () => {
    renderImport();

    await screen.findByTestId('statement-dropzone');
    expect(screen.queryByTestId('retry-import-banner')).not.toBeInTheDocument();
  });

  /**
   * Bug fix, caught by review: reimportState/resumeState/retryState are derived fresh every
   * render straight from location.state, which react-router does not clear on its own. Without
   * startOver() explicitly clearing it, finishing a retried import and clicking "Import Another"
   * for a completely unrelated file left the stale "Retrying <file>" banner still showing.
   */
  it('clears the retry banner once the person starts a fresh import via "Import Another"', async () => {
    vi.mocked(importApi.stageCsv).mockResolvedValue(stagingResultWith());
    vi.mocked(importApi.confirm).mockResolvedValue({
      imported: 1, skipped: 0, duplicatesDetected: 0, transfersIdentified: 0, newMerchantsLearned: 0,
      accountsCreated: [], productsCreated: {}, categoriesAssigned: {}, warnings: [], account: null,
      totalCredits: 0, totalDebits: 0, statementOpeningBalance: null, statementClosingBalance: null,
      statementPeriodStart: null, statementPeriodEnd: null, importDurationMs: 12, source: 'CSV',
    } as never);
    const user = userEvent.setup();
    renderImportWithRetryState('bad-statement.pdf', NO_HEADER_DETECTED);

    await screen.findByTestId('retry-import-banner');
    await user.upload(screen.getByTestId('statement-file-input'), csvFile());
    await screen.findByText(/which account is this statement for/i);
    await user.click(screen.getByRole('button', { name: /confirm import/i }));
    await user.click(await screen.findByRole('button', { name: /import another/i }));

    await screen.findByTestId('statement-dropzone');
    // Bug fix (CI flake, same root cause as the "cancelled"/"dismissed" siblings above):
    // startOver()'s own clearArrivalState() call -- what actually clears retryState -- lands on
    // a later render than the setStep('upload') that makes the dropzone reappear, since router
    // navigation and local useState updates commit on separate ticks. findByTestId resolving
    // only proves the dropzone is back, not that retryState has cleared yet.
    await waitFor(() => expect(screen.queryByTestId('retry-import-banner')).not.toBeInTheDocument());
  });
});

/**
 * docs/proposals/account-ownership-intelligence-proposal.md §3.1: the client-side pre-check that
 * decides whether to show the "Statement Check" dialog before confirming. The backend's own
 * OwnershipMatchService (which persists the authoritative result) is covered separately in
 * ImportServiceSessionTest -- this only covers the frontend gate.
 */
describe('Import — ownership name-mismatch warning', () => {
  function detectedAccountWithHolder(accountHolderName: string | null) {
    return { ...detectedAccount, accountHolderName };
  }

  function stageWithHolder(accountHolderName: string | null) {
    vi.mocked(importApi.stagePdf).mockReset().mockResolvedValue({
      sessionId: 'session-1',
      multiAccount: false,
      sections: null,
      staging: {
        rows: [],
        totalParsed: 0,
        flaggedDuplicates: 0,
        detectedAccount: detectedAccountWithHolder(accountHolderName),
        unparseableRows: [],
      },
    } as never);
  }

  beforeEach(() => {
    localStorage.setItem('finora_name', 'Rahul Sharma');
    vi.mocked(categoriesApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(accountsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(importApi.confirm).mockReset().mockResolvedValue({
      imported: 1, skipped: 0, duplicatesDetected: 0, transfersIdentified: 0, newMerchantsLearned: 0,
      accountsCreated: [], productsCreated: {}, categoriesAssigned: {}, warnings: [], account: null,
      totalCredits: 0, totalDebits: 0, statementOpeningBalance: null, statementClosingBalance: null,
      statementPeriodStart: null, statementPeriodEnd: null, importDurationMs: 12, source: 'PDF',
    } as never);
  });

  afterEach(() => localStorage.removeItem('finora_name'));

  it('shows the warning and does not confirm yet when the holder name differs from the profile', async () => {
    stageWithHolder('Sunil Verma');
    const user = userEvent.setup();
    renderImport();

    await pickAndUploadPdf(user);
    await user.click(screen.getByRole('button', { name: /confirm import/i }));

    expect(await screen.findByText('Statement Check')).toBeInTheDocument();
    expect(screen.getByText(/Sunil Verma/)).toBeInTheDocument();
    expect(screen.getByText(/Rahul Sharma/)).toBeInTheDocument();
    expect(importApi.confirm).not.toHaveBeenCalled();
  });

  it('confirms with userConfirmedContinue once the user clicks Continue Import', async () => {
    stageWithHolder('Sunil Verma');
    const user = userEvent.setup();
    renderImport();

    await pickAndUploadPdf(user);
    await user.click(screen.getByRole('button', { name: /confirm import/i }));
    await screen.findByText('Statement Check');
    await user.click(screen.getByRole('button', { name: 'Continue Import' }));

    await waitFor(() => expect(importApi.confirm).toHaveBeenCalledTimes(1));
    expect(importApi.confirm).toHaveBeenCalledWith(
      expect.objectContaining({ userConfirmedContinue: true }),
    );
  });

  it('returns to the upload step without confirming when the user picks Upload Different Statement', async () => {
    stageWithHolder('Sunil Verma');
    const user = userEvent.setup();
    renderImport();

    await pickAndUploadPdf(user);
    await user.click(screen.getByRole('button', { name: /confirm import/i }));
    await screen.findByText('Statement Check');
    await user.click(screen.getByRole('button', { name: 'Upload Different Statement' }));

    await screen.findByTestId('statement-dropzone');
    expect(importApi.confirm).not.toHaveBeenCalled();
  });

  it('never shows the warning when the holder name matches the profile', async () => {
    stageWithHolder('Rahul Sharma');
    const user = userEvent.setup();
    renderImport();

    await pickAndUploadPdf(user);
    await user.click(screen.getByRole('button', { name: /confirm import/i }));

    await waitFor(() => expect(importApi.confirm).toHaveBeenCalledTimes(1));
    expect(screen.queryByText('Statement Check')).not.toBeInTheDocument();
    expect(importApi.confirm).toHaveBeenCalledWith(
      expect.objectContaining({ userConfirmedContinue: undefined }),
    );
  });

  it('never shows the warning when the statement has no extractable holder name', async () => {
    stageWithHolder(null);
    const user = userEvent.setup();
    renderImport();

    await pickAndUploadPdf(user);
    await user.click(screen.getByRole('button', { name: /confirm import/i }));

    await waitFor(() => expect(importApi.confirm).toHaveBeenCalledTimes(1));
    expect(screen.queryByText('Statement Check')).not.toBeInTheDocument();
  });

  it('never shows the warning when the profile itself has no name on file', async () => {
    // fullName is genuinely nullable -- Apple Sign-In only supplies it on the first authorization
    // (AuthContext's loginWithApple). Without the guard this regression-tests, isLikelyMatch(holder,
    // null) always returns false, so EVERY import with an extractable holder name would warn,
    // forever, for a user in this state -- and the dialog would show "null" as their profile name.
    localStorage.removeItem('finora_name');
    stageWithHolder('Sunil Verma');
    const user = userEvent.setup();
    renderImport();

    await pickAndUploadPdf(user);
    await user.click(screen.getByRole('button', { name: /confirm import/i }));

    await waitFor(() => expect(importApi.confirm).toHaveBeenCalledTimes(1));
    expect(screen.queryByText('Statement Check')).not.toBeInTheDocument();
  });
});
