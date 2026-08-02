import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Import from './Import';
import { importApi, categoriesApi, accountsApi } from '../api/endpoints';
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

    await user.upload(screen.getByTestId('statement-file-input'), pdfFile());

    await waitFor(() => expect(importApi.stagePdf).toHaveBeenCalledTimes(1));
    expect(importApi.stagePdf).toHaveBeenCalledWith(expect.objectContaining({ name: 'statement.pdf' }), expect.any(Function));
    expect(importApi.stageCsv).not.toHaveBeenCalled();
  });

  it('is case-insensitive about the extension (e.g. STATEMENT.PDF)', async () => {
    const user = userEvent.setup();
    renderImport();

    await user.upload(screen.getByTestId('statement-file-input'), pdfFile('STATEMENT.PDF'));

    await waitFor(() => expect(importApi.stagePdf).toHaveBeenCalledTimes(1));
    expect(importApi.stageCsv).not.toHaveBeenCalled();
  });

  it('stages a .pdf file dropped onto the dropzone', async () => {
    renderImport();

    fireEvent.drop(screen.getByTestId('statement-dropzone'), { dataTransfer: { files: [pdfFile()] } });

    await waitFor(() => expect(importApi.stagePdf).toHaveBeenCalledTimes(1));
    expect(importApi.stageCsv).not.toHaveBeenCalled();
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

    await user.upload(screen.getByTestId('statement-file-input'), pdfFile());

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

    await user.upload(screen.getByTestId('statement-file-input'), pdfFile());

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

    await user.upload(screen.getByTestId('statement-file-input'), pdfFile());

    expect(await screen.findByText(/unable to reach the import service/i)).toBeInTheDocument();
    expect(screen.queryByText(/could not parse this pdf/i)).not.toBeInTheDocument();
  });
});
