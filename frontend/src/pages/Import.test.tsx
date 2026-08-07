import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Import from './Import';
import { importApi, importJobsApi, categoriesApi, accountsApi, type ImportJobProgress } from '../api/endpoints';
import type { StagedAccountSection } from '../types';
import { PDF_PASSWORD_REQUIRED, PDF_PASSWORD_INVALID } from '../api/errorCodes';
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
});

function renderImport() {
  const queryClient = new QueryClient();
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <Import />
      </MemoryRouter>
    </QueryClientProvider>
  );
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

    expect(await screen.findByText(/unable to reach the import service/i)).toBeInTheDocument();
    expect(screen.queryByText(/could not parse this pdf/i)).not.toBeInTheDocument();
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

    // It's the bank's password for one document, not a Finora credential -- saving it into the
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
    status: 'QUEUED',
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

  it('stops an import the user changed their mind about', async () => {
    vi.mocked(importJobsApi.progress).mockResolvedValue(queuedJob({ status: 'PARSING' }));
    vi.mocked(importJobsApi.cancel).mockResolvedValue(queuedJob({ status: 'CANCELLED' }));
    const user = userEvent.setup();
    renderImport();
    await waitFor(() => expect(importJobsApi.availability).toHaveBeenCalled());

    await user.upload(screen.getByTestId('statement-file-input'), csvFile());
    await screen.findByTestId('import-progress');

    await user.click(screen.getByRole('button', { name: 'Cancel' }));

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

  it('surfaces the server’s reason when an import fails for good', async () => {
    vi.mocked(importJobsApi.progress).mockResolvedValue(queuedJob({
      status: 'FAILED', error: 'No transactions could be read from this statement.',
    }));
    const user = userEvent.setup();
    renderImport();
    await waitFor(() => expect(importJobsApi.availability).toHaveBeenCalled());

    await user.upload(screen.getByTestId('statement-file-input'), csvFile());

    expect(await screen.findByText('No transactions could be read from this statement.'))
      .toBeInTheDocument();
  });
});
