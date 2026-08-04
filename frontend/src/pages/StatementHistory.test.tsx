import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import StatementHistory from './StatementHistory';
import { statementImportsApi } from '../api/endpoints';
import { PDF_PASSWORD_INVALID, PDF_PASSWORD_REQUIRED } from '../api/errorCodes';
import type { AccountStatementGroup } from '../types';

// Scoped to re-importing a password-protected statement -- the one flow on this page where the
// server's answer changes what the page DOES rather than only what it says. Everything else here
// (grouping, delete, download, the detail modals) is untouched by that change.
vi.mock('../api/endpoints', () => ({
  statementImportsApi: {
    listGroupedByAccount: vi.fn(),
    reimport: vi.fn(),
    remove: vi.fn(),
    downloadFile: vi.fn(),
    transactions: vi.fn(),
  },
}));

const navigate = vi.fn();
vi.mock('react-router-dom', async (importOriginal) => ({
  ...(await importOriginal<typeof import('react-router-dom')>()),
  useNavigate: () => navigate,
}));

const bank = {
  id: 'OTHER', officialName: null, shortName: 'Other', colorHex: '#000000', initials: 'OT',
  logoPath: '', category: null, websiteUrl: null, ifscPrefix: null, supportedAccountTypes: [],
};

// One group only: the page auto-expands a lone account, so the statement row (and its re-import
// button) is on screen without having to drive the disclosure open first.
const groups: AccountStatementGroup[] = [{
  accountId: 'acct-1',
  accountName: 'HDFC Savings',
  accountType: 'SAVINGS',
  bank,
  deleted: false,
  deletedAt: null,
  statements: [{
    id: 'stmt-1',
    fileName: 'protected-statement.pdf',
    statementPeriodStart: null,
    statementPeriodEnd: null,
    openingBalance: null,
    closingBalance: null,
    transactionsImported: 12,
    transactionsSkipped: 0,
    status: 'COMPLETED',
    importedAt: '2026-08-01T10:00:00Z',
    duplicateCount: 0,
  }],
}];

function reimportResult() {
  return {
    staging: {
      rows: [], totalParsed: 0, flaggedDuplicates: 0, unparseableRows: [],
      detectedAccount: {} as never,
    },
    accountId: 'acct-1',
    accountName: 'HDFC Savings',
  };
}

function rejectWith(errorCode: string) {
  return { response: { data: { errorCode, message: 'server copy' } } };
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <StatementHistory />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

// The row's action button and the modal's submit share an accessible name ("Re-import
// Statement"), so the submit has to be looked up inside the modal rather than page-wide.
function submitButton() {
  return within(screen.getByTestId('reimport-password-modal')).getByRole('button', {
    name: /re-import statement/i,
  });
}

async function clickReimport(user: ReturnType<typeof userEvent.setup>) {
  const button = await screen.findByTitle('Re-import Statement');
  await user.click(button);
}

describe('StatementHistory — re-importing a password-protected statement', () => {
  beforeEach(() => {
    navigate.mockReset();
    vi.mocked(statementImportsApi.listGroupedByAccount).mockReset().mockResolvedValue(groups);
    vi.mocked(statementImportsApi.reimport).mockReset().mockResolvedValue(reimportResult());
  });

  it('re-imports in one click when no password is needed', async () => {
    const user = userEvent.setup();
    renderPage();

    await clickReimport(user);

    // The majority case. Trying first is what keeps it a single click -- offering a password field
    // up front, as the upload flow does, would tax every unprotected statement to help a few.
    await waitFor(() => expect(statementImportsApi.reimport).toHaveBeenCalledWith('stmt-1', undefined));
    expect(screen.queryByTestId('reimport-password-modal')).not.toBeInTheDocument();
    expect(navigate).toHaveBeenCalledTimes(1);
  });

  it('prompts for the password when the stored file turns out to be protected', async () => {
    vi.mocked(statementImportsApi.reimport).mockReset().mockRejectedValue(rejectWith(PDF_PASSWORD_REQUIRED));
    const user = userEvent.setup();
    renderPage();

    await clickReimport(user);

    expect(await screen.findByTestId('reimport-password-modal')).toBeInTheDocument();
    // A locked statement is not a failed re-import, and saying so would send someone looking for a
    // problem with the statement instead of for the password.
    expect(screen.queryByText(/could not re-import this statement/i)).not.toBeInTheDocument();
    expect(navigate).not.toHaveBeenCalled();
  });

  it('retries with the password and continues to the review step', async () => {
    vi.mocked(statementImportsApi.reimport).mockReset()
      .mockRejectedValueOnce(rejectWith(PDF_PASSWORD_REQUIRED))
      .mockResolvedValueOnce(reimportResult());
    const user = userEvent.setup();
    renderPage();

    await clickReimport(user);
    await screen.findByTestId('reimport-password-modal');

    await user.type(screen.getByLabelText(/statement password/i), 'AAAA1234');
    await user.click(submitButton());

    await waitFor(() => expect(statementImportsApi.reimport).toHaveBeenCalledTimes(2));
    expect(statementImportsApi.reimport).toHaveBeenLastCalledWith('stmt-1', 'AAAA1234');
    await waitFor(() => expect(navigate).toHaveBeenCalledTimes(1));
    // The modal must close on success, or the review screen opens underneath it.
    await waitFor(() => expect(screen.queryByTestId('reimport-password-modal')).not.toBeInTheDocument());
  });

  it('keeps the prompt open with an inline error when the password is rejected', async () => {
    vi.mocked(statementImportsApi.reimport).mockReset().mockRejectedValue(rejectWith(PDF_PASSWORD_REQUIRED));
    const user = userEvent.setup();
    renderPage();

    await clickReimport(user);
    await screen.findByTestId('reimport-password-modal');

    vi.mocked(statementImportsApi.reimport).mockRejectedValue(rejectWith(PDF_PASSWORD_INVALID));
    await user.type(screen.getByLabelText(/statement password/i), 'WRONG999');
    await user.click(submitButton());

    expect(await screen.findByText(/didn't open this statement/i)).toBeInTheDocument();
    expect(screen.getByTestId('reimport-password-modal')).toBeInTheDocument();
  });

  it('still reports a genuine re-import failure as an error, not as a password problem', async () => {
    vi.mocked(statementImportsApi.reimport).mockReset()
      .mockRejectedValue({ response: { data: { errorCode: 'GEN_002', message: 'Unexpected error' } } });
    const user = userEvent.setup();
    renderPage();

    await clickReimport(user);

    expect(await screen.findByText('Unexpected error')).toBeInTheDocument();
    expect(screen.queryByTestId('reimport-password-modal')).not.toBeInTheDocument();
  });

  it('does not offer the password to a browser password manager', async () => {
    vi.mocked(statementImportsApi.reimport).mockReset().mockRejectedValue(rejectWith(PDF_PASSWORD_REQUIRED));
    const user = userEvent.setup();
    renderPage();

    await clickReimport(user);

    expect(await screen.findByLabelText(/statement password/i)).toHaveAttribute('autocomplete', 'off');
  });
});
