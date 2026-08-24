import { act, render, screen, waitFor, fireEvent } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { StatementHistoryScreen } from './StatementHistoryScreen';
import { statementImportsApi } from '../api/endpoints';
import { PDF_PASSWORD_INVALID, PDF_PASSWORD_REQUIRED } from '../api/errorCodes';
import type { AccountStatementGroup } from '../types';

// Scoped to re-importing a password-protected statement -- the one flow here where the server's
// answer changes what the screen DOES rather than only what it says.
jest.mock('../api/endpoints', () => ({
  statementImportsApi: {
    listGroupedByAccount: jest.fn(),
    reimport: jest.fn(),
    remove: jest.fn(),
    downloadFile: jest.fn(),
    transactions: jest.fn(),
  },
}));

// `mock`-prefixed so Jest allows the factory below to close over it -- the factory is hoisted
// above this declaration, and that prefix is the documented opt-out.
const mockNavigateToImport = jest.fn();
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ getParent: () => ({ navigate: mockNavigateToImport }) }),
}));

jest.mock('react-native-safe-area-context', () => ({
  useSafeAreaInsets: () => ({ top: 0, bottom: 0, left: 0, right: 0 }),
}));

const api = statementImportsApi as jest.Mocked<typeof statementImportsApi>;

const bank = {
  id: 'OTHER', officialName: null, shortName: 'Other', colorHex: '#000000', initials: 'OT',
  logoPath: '', category: null, websiteUrl: null, ifscPrefix: null, supportedAccountTypes: [],
};

// One group only: a lone account auto-expands, so the statement row's actions are on screen
// without having to drive the disclosure open first.
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
  // Matches what apiErrorCode() reads: an axios error whose response body carries errorCode.
  return Object.assign(new Error('Request failed'), {
    isAxiosError: true,
    response: { status: 422, data: { errorCode, message: 'server copy' } },
  });
}

function renderScreen() {
  // gcTime 0 so the cache is collected as soon as the screen unmounts. Left at its default, the
  // client keeps a garbage-collection timer alive past teardown and Jest reports a worker that
  // would not exit -- noise in a full run, invisible when this file runs alone.
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <StatementHistoryScreen />
    </QueryClientProvider>
  );
}

async function tapReimport() {
  fireEvent.press(await screen.findByLabelText('Re-import'));
  await settle();
}

/** Types a password into the unlock prompt and submits it. */
async function submitPassword(value: string) {
  fireEvent.changeText(screen.getByLabelText('Statement password'), value);
  // The shared Button exposes its label as text, not as an accessibilityLabel.
  fireEvent.press(screen.getByText('Re-import statement'));
  await settle();
}

/**
 * Lets handleReimport's `finally { setBusyId(null) }` land before assertions run.
 *
 * Without it a test returns while that last state update is still queued, React applies it to an
 * already-unmounted tree, and the run fills with act() warnings. It has to happen inside the test
 * -- an afterEach runs after RNTL has already unmounted, which is too late to help.
 */
async function settle() {
  await act(async () => {});
}

describe('StatementHistoryScreen — re-importing a password-protected statement', () => {
  beforeEach(() => {
    mockNavigateToImport.mockReset();
    api.listGroupedByAccount.mockReset().mockResolvedValue(groups);
    api.reimport.mockReset().mockResolvedValue(reimportResult());
  });

  it('re-imports in one tap when no password is needed', async () => {
    renderScreen();

    await tapReimport();

    // The majority case. Trying first is what keeps it a single tap -- offering a password field
    // up front, as the upload flow does, would tax every unprotected statement to help a few.
    await waitFor(() => expect(api.reimport).toHaveBeenCalledWith('stmt-1', undefined));
    expect(screen.queryByLabelText('Statement password')).toBeNull();
  });

  it('hands the staged rows to the Import tab rather than reviewing them here', async () => {
    renderScreen();

    await tapReimport();

    await waitFor(() => expect(mockNavigateToImport).toHaveBeenCalledTimes(1));
    const [screenName, params] = mockNavigateToImport.mock.calls[0];
    expect(screenName).toBe('Import');
    expect(params.reimport).toMatchObject({ statementImportId: 'stmt-1', accountId: 'acct-1' });
    // The nonce is what lets the Import tab tell a fresh arrival from a stale param it has
    // already consumed -- without it, tapping Import later would re-enter this same re-import.
    expect(typeof params.reimport.nonce).toBe('number');
  });

  it('prompts for the password when the stored file turns out to be protected', async () => {
    api.reimport.mockReset().mockRejectedValue(rejectWith(PDF_PASSWORD_REQUIRED));
    renderScreen();

    await tapReimport();

    expect(await screen.findByLabelText('Statement password')).toBeTruthy();
    // A locked statement is not a failed re-import, and saying so would send someone looking for a
    // problem with the statement instead of for the password.
    expect(screen.queryByText(/could not re-import this statement/i)).toBeNull();
    expect(mockNavigateToImport).not.toHaveBeenCalled();
  });

  it('retries with the password and continues to the Import tab', async () => {
    api.reimport.mockReset()
      .mockRejectedValueOnce(rejectWith(PDF_PASSWORD_REQUIRED))
      .mockResolvedValueOnce(reimportResult());
    renderScreen();

    await tapReimport();
    await screen.findByLabelText('Statement password');

    await submitPassword('AAAA1234');

    await waitFor(() => expect(api.reimport).toHaveBeenCalledTimes(2));
    expect(api.reimport).toHaveBeenLastCalledWith('stmt-1', 'AAAA1234');
    await waitFor(() => expect(mockNavigateToImport).toHaveBeenCalledTimes(1));

    // The regression this guards: the password unlocked staging, but confirmReimport() re-parses
    // the same stored bytes server-side and needs it again -- ConfirmRequest had nowhere to carry
    // it, so every reimport-confirm of a protected statement failed unconditionally regardless of
    // whether the password above was ever correct. Dropping it here, before the Import tab ever
    // sees it, is exactly how that happened. See StatementImportService.confirmReimport's doc
    // comment for the incident this is named after.
    const [, params] = mockNavigateToImport.mock.calls[0];
    expect(params.reimport.password).toBe('AAAA1234');
  });

  it('does not invent a password for a statement that never needed one', async () => {
    renderScreen();

    await tapReimport();

    await waitFor(() => expect(mockNavigateToImport).toHaveBeenCalledTimes(1));
    const [, params] = mockNavigateToImport.mock.calls[0];
    expect(params.reimport.password).toBeUndefined();
  });

  it('keeps the prompt open with an inline error when the password is rejected', async () => {
    api.reimport.mockReset().mockRejectedValue(rejectWith(PDF_PASSWORD_REQUIRED));
    renderScreen();

    await tapReimport();
    await screen.findByLabelText('Statement password');

    api.reimport.mockRejectedValue(rejectWith(PDF_PASSWORD_INVALID));
    await submitPassword('WRONG999');

    expect(await screen.findByText(/didn't open this statement/i)).toBeTruthy();
    // Still open, and still holding what was typed -- clearing it reads as though the app lost
    // the statement.
    expect(screen.getByLabelText('Statement password').props.value).toBe('WRONG999');
  });

  it('still reports a genuine re-import failure as an error, not as a password problem', async () => {
    api.reimport.mockReset().mockRejectedValue(rejectWith('GEN_002'));
    renderScreen();

    await tapReimport();

    expect(await screen.findByText('server copy')).toBeTruthy();
    expect(screen.queryByLabelText('Statement password')).toBeNull();
  });

  it('does not offer the statement password to the OS keychain', async () => {
    api.reimport.mockReset().mockRejectedValue(rejectWith(PDF_PASSWORD_REQUIRED));
    renderScreen();

    await tapReimport();
    const field = await screen.findByLabelText('Statement password');

    // The bank's password for one document, not a Fynora credential.
    expect(field.props.autoComplete).toBe('off');
    expect(field.props.textContentType).toBe('none');
    expect(field.props.secureTextEntry).toBe(true);
  });

  it('does not offer re-import for a statement whose account was deleted', async () => {
    api.listGroupedByAccount.mockReset().mockResolvedValue([
      { ...groups[0], deleted: true, deletedAt: '2026-08-01T00:00:00Z' },
    ]);
    renderScreen();

    // There is nowhere to replay the rows into, so the action is present but not usable rather
    // than silently failing at the server.
    const button = await screen.findByLabelText('Re-import');
    expect(button.props.accessibilityState.disabled).toBe(true);
  });
});
