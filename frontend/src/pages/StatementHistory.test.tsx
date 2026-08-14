import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import StatementHistory from './StatementHistory';
import { statementImportsApi, importApi, importJobsApi } from '../api/endpoints';
import type { ImportJobProgress } from '../api/endpoints';
import { PDF_PASSWORD_INVALID, PDF_PASSWORD_REQUIRED, NO_HEADER_DETECTED } from '../api/errorCodes';
import { IMPORT_FAILURE_MESSAGES } from '../api/importFailureMessages';
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
  importApi: {
    listFailures: vi.fn(),
  },
  importJobsApi: {
    recent: vi.fn(),
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
    // No failed imports by default -- these tests are about the re-import flow, not the failures
    // section, which has its own describe block below.
    vi.mocked(importApi.listFailures).mockReset().mockResolvedValue([]);
    // Same reasoning: no in-progress jobs by default, so this section stays out of the way of the
    // re-import tests. It has its own describe block below.
    vi.mocked(importJobsApi.recent).mockReset().mockResolvedValue([]);
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

  /** Mirrors the exact-shape assertions the resume and retry nav-state call sites already have --
   *  this route now has three tagged arrival shapes, and this is the one whose own bug (a blind
   *  truthiness cast, fixed in Premium Import Reliability v1 §3.2) motivated tagging all of them
   *  with `kind` in the first place, so it should not be the one shape left unverified. */
  it('navigates with the exact reimport-tagged state shape', async () => {
    const user = userEvent.setup();
    renderPage();

    await clickReimport(user);

    await waitFor(() => expect(navigate).toHaveBeenCalledWith('/app/import', {
      state: {
        kind: 'reimport',
        reimportId: 'stmt-1',
        staging: reimportResult().staging,
        accountId: 'acct-1',
        accountName: 'HDFC Savings',
        password: undefined,
      },
    }));
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

    // The regression this guards: the password unlocked staging, but confirmReimport() re-parses
    // the same stored bytes server-side and needs it again -- ConfirmRequest had nowhere to carry
    // it, so every reimport-confirm of a protected statement failed unconditionally regardless of
    // whether the password above was ever correct. Dropping it here, before Import ever sees it, is
    // exactly how that happened. See StatementImportService.confirmReimport's doc comment for the
    // incident this is named after.
    expect(navigate.mock.calls[0][1].state.password).toBe('AAAA1234');
  });

  it('does not invent a password for a statement that never needed one', async () => {
    const user = userEvent.setup();
    renderPage();

    await clickReimport(user);

    await waitFor(() => expect(navigate).toHaveBeenCalledTimes(1));
    expect(navigate.mock.calls[0][1].state.password).toBeUndefined();
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

/**
 * Premium Import Reliability v1, §2.1's frontend slice: GET /import/failures displayed as its own
 * section, independent of the account-groups list a failed import never joined. Reuses the same
 * failure UX contract (importFailureMessages.ts) Import.tsx's live upload flow already draws on --
 * a failure a user comes back to later reads the same way one they hit live does.
 */
describe('StatementHistory — failed imports', () => {
  beforeEach(() => {
    navigate.mockReset();
    vi.mocked(statementImportsApi.listGroupedByAccount).mockReset().mockResolvedValue(groups);
    vi.mocked(importJobsApi.recent).mockReset().mockResolvedValue([]);
  });

  function aFailure(overrides: Partial<{ reference: string; fileName: string; failureCode: string | null; createdAt: string }> = {}) {
    return {
      reference: 'SA-20260812-0001',
      fileName: 'unreadable-statement.pdf',
      failureCode: NO_HEADER_DETECTED,
      createdAt: '2026-08-12T10:00:00Z',
      ...overrides,
    };
  }

  it('shows the curated contract message for a known failure code', async () => {
    vi.mocked(importApi.listFailures).mockReset().mockResolvedValue([aFailure()]);
    renderPage();

    expect(await screen.findByText('unreadable-statement.pdf')).toBeInTheDocument();
    expect(screen.getByText(IMPORT_FAILURE_MESSAGES[NO_HEADER_DETECTED].message)).toBeInTheDocument();
  });

  it('falls back to a safe generic message for an unmapped or missing failure code', async () => {
    vi.mocked(importApi.listFailures).mockReset()
      .mockResolvedValue([aFailure({ fileName: 'mystery-failure.csv', failureCode: null })]);
    renderPage();

    expect(await screen.findByText('mystery-failure.csv')).toBeInTheDocument();
    expect(screen.getByText(/couldn't complete this import/i)).toBeInTheDocument();
  });

  it('shows no Failed Imports section at all when there are no failures', async () => {
    vi.mocked(importApi.listFailures).mockReset().mockResolvedValue([]);
    renderPage();

    // Give the successful-imports list (which always renders) a chance to land first, so this
    // isn't just "the failures query hasn't resolved yet".
    await screen.findByText('HDFC Savings');
    expect(screen.queryByText('Failed Imports')).not.toBeInTheDocument();
  });

  /** Premium Import Reliability v1, §2.5 -- a failed sync import has no bytes retained, so this
   *  can only send the person back to Import with context, not replay the original upload the
   *  way confirmed "Reimport" does. Carries the raw failureCODE, not a pre-curated message --
   *  Import.tsx does its own curation at render time, so the two pages can't drift in wording for
   *  the same code. */
  it('sends "Try again" to Import with the file name and raw failure code as context', async () => {
    vi.mocked(importApi.listFailures).mockReset().mockResolvedValue([aFailure()]);
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('button', { name: /try again/i }));

    expect(navigate).toHaveBeenCalledWith('/app/import', {
      state: {
        kind: 'retry',
        retryFileName: 'unreadable-statement.pdf',
        retryFailureCode: NO_HEADER_DETECTED,
      },
    });
  });

  it('does not affect the account-groups list, re-import, or delete flows', async () => {
    vi.mocked(importApi.listFailures).mockReset().mockResolvedValue([aFailure()]);
    renderPage();

    await screen.findByText('Failed Imports');
    // The statement history this page exists to show is completely unaffected by a failure
    // appearing alongside it.
    expect(screen.getByText('HDFC Savings')).toBeInTheDocument();
    expect(screen.getByTitle('Re-import Statement')).toBeInTheDocument();
  });

  it('fails closed: a broken failures query never blanks or blocks the rest of the page', async () => {
    // React Query does not throw or surface a query's own error to the page without an explicit
    // opt-in this call never makes -- this proves that stays true rather than just asserting it in
    // a comment. The account-groups list is the far more important thing on this page and must
    // render normally even when this secondary panel's own request fails outright.
    vi.mocked(importApi.listFailures).mockReset().mockRejectedValue(new Error('network error'));
    renderPage();

    expect(await screen.findByText('HDFC Savings')).toBeInTheDocument();
    expect(screen.getByTitle('Re-import Statement')).toBeInTheDocument();
    expect(screen.queryByText('Failed Imports')).not.toBeInTheDocument();
  });
});

/**
 * The entry point to the self-service import detail page (Premium Import Reliability v1, §3.2) --
 * without this section, `/app/imports/:jobId` is reachable only by typing a UUID into the address
 * bar. `importJobsApi.recent()` is used for the first time here.
 */
describe('StatementHistory — recent imports', () => {
  beforeEach(() => {
    navigate.mockReset();
    vi.mocked(statementImportsApi.listGroupedByAccount).mockReset().mockResolvedValue(groups);
    vi.mocked(importApi.listFailures).mockReset().mockResolvedValue([]);
  });

  function aJob(overrides: Partial<ImportJobProgress> = {}): ImportJobProgress {
    return {
      jobId: 'job-1',
      fileName: 'still-going.csv',
      status: 'PARSING',
      userStatus: 'PROCESSING',
      rowsTotal: null,
      rowsProcessed: 0,
      createdAt: '2026-08-13T09:00:00Z',
      startedAt: '2026-08-13T09:00:01Z',
      finishedAt: null,
      importSessionId: null,
      error: null,
      correlationId: null,
      ...overrides,
    };
  }

  it('lists a job the worker is still holding', async () => {
    vi.mocked(importJobsApi.recent).mockReset().mockResolvedValue([aJob()]);
    renderPage();

    expect(await screen.findByText('still-going.csv')).toBeInTheDocument();
    expect(screen.getByText('Recent Imports')).toBeInTheDocument();
  });

  it('excludes a completed job -- it is already surfaced by "Continue previous import" instead', async () => {
    // A COMPLETED async job already has a real staged ImportSession, created through the same code
    // path the synchronous upload endpoints use, and Import.tsx's own unfinished-sessions list
    // already shows it. Listing it here too would show the same staged review twice, in two
    // different shapes.
    vi.mocked(importJobsApi.recent).mockReset()
      .mockResolvedValue([aJob({ jobId: 'job-done', fileName: 'done.csv', status: 'COMPLETED', importSessionId: 'session-1' })]);
    renderPage();

    await screen.findByText('HDFC Savings');
    expect(screen.queryByText('Recent Imports')).not.toBeInTheDocument();
    expect(screen.queryByText('done.csv')).not.toBeInTheDocument();
  });

  it('renders no section at all when there is nothing in progress', async () => {
    vi.mocked(importJobsApi.recent).mockReset().mockResolvedValue([]);
    renderPage();

    await screen.findByText('HDFC Savings');
    expect(screen.queryByText('Recent Imports')).not.toBeInTheDocument();
  });

  it('navigates to the detail page for the job that was clicked', async () => {
    vi.mocked(importJobsApi.recent).mockReset().mockResolvedValue([
      aJob({ jobId: 'job-abc', fileName: 'still-going.csv' }),
    ]);
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByText('still-going.csv'));

    expect(navigate).toHaveBeenCalledWith('/app/imports/job-abc');
  });

  it('fails closed: a broken recent-jobs query never blanks or blocks the rest of the page', async () => {
    vi.mocked(importJobsApi.recent).mockReset().mockRejectedValue(new Error('network error'));
    renderPage();

    expect(await screen.findByText('HDFC Savings')).toBeInTheDocument();
    expect(screen.getByTitle('Re-import Statement')).toBeInTheDocument();
    expect(screen.queryByText('Recent Imports')).not.toBeInTheDocument();
  });

  /**
   * Bug fix, caught by review: this query has no staleTime override of its own before this fix,
   * so it inherits the app's real global default (App.tsx: 30s, refetchOnWindowFocus off) -- a
   * job that finished or failed while the user was on a different page kept showing its
   * last-fetched in-flight status even after navigating back, for up to 30 seconds. Uses a
   * QueryClient configured with that SAME 30s default (renderPage()'s own test client doesn't set
   * one, so it's already effectively zero and wouldn't catch this) to prove the override on this
   * specific query actually beats it, not just that a from-scratch fetch happens to look fresh.
   */
  it('shows a job that just changed status even when revisited well inside the app-wide 30s staleTime', async () => {
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false, staleTime: 30_000, refetchOnWindowFocus: false } },
    });
    vi.mocked(importJobsApi.recent).mockReset().mockResolvedValueOnce([aJob({ status: 'PARSING' })]);

    const { unmount } = render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter><StatementHistory /></MemoryRouter>
      </QueryClientProvider>
    );
    await screen.findByText('still-going.csv');
    unmount();

    // The job settled while the user was away; the SAME client (same 30s-stale cache) is reused
    // for the revisit, exactly like navigating back within the app rather than a fresh page load.
    vi.mocked(importJobsApi.recent).mockResolvedValueOnce([aJob({ status: 'FAILED' })]);
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter><StatementHistory /></MemoryRouter>
      </QueryClientProvider>
    );

    await waitFor(() => expect(importJobsApi.recent).toHaveBeenCalledTimes(2));
  });
});
