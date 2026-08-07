import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Import from './Import';
import { importApi, categoriesApi, accountsApi } from '../api/endpoints';
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
