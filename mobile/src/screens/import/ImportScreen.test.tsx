import { act, render, screen, fireEvent } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ImportScreen } from './ImportScreen';
import { accountsApi, categoriesApi, importApi, statementImportsApi } from '../../api/endpoints';
import type { DetectedAccountInfo, StagedRow } from '../../types';

// Only the re-import arrival path is exercised here, so every staging/upload call is a stub that
// nothing in this file expects to be reached.
jest.mock('../../api/endpoints', () => ({
  accountsApi: { list: jest.fn() },
  categoriesApi: { list: jest.fn() },
  importApi: {
    listSessions: jest.fn(),
    getSession: jest.fn(),
    discardSession: jest.fn(),
    stageCsv: jest.fn(),
    stagePdf: jest.fn(),
    confirm: jest.fn(),
  },
  statementImportsApi: { confirmReimport: jest.fn() },
}));

// This screen calls DocumentPicker only from handlePick, never reached below -- mocked purely so
// importing statementFile.ts (a static import of ImportScreen.tsx) doesn't touch the native module.
jest.mock('expo-document-picker', () => ({ getDocumentAsync: jest.fn() }));

jest.mock('react-native-safe-area-context', () => ({
  useSafeAreaInsets: () => ({ top: 0, bottom: 0, left: 0, right: 0 }),
}));

// A controllable stand-in for useRoute -- StatementHistoryScreen.test.tsx's file-level
// jest.mock('@react-navigation/native') pattern, extended with the one hook this screen calls that
// screen didn't. mockNavigate (Track C/C6) is a plain jest.fn(): this screen only ever calls
// navigate() to leave, never asserts on the result of being navigated TO, so nothing here needs
// the shared-getParent()-stub shape the More-stack screens' own test files use.
let mockRouteParams: { reimport?: unknown } | undefined;
const mockNavigate = jest.fn();
jest.mock('@react-navigation/native', () => ({
  useRoute: () => ({ params: mockRouteParams }),
  useNavigation: () => ({ navigate: mockNavigate }),
}));

const api = {
  accounts: accountsApi as jest.Mocked<typeof accountsApi>,
  categories: categoriesApi as jest.Mocked<typeof categoriesApi>,
  import: importApi as jest.Mocked<typeof importApi>,
  statements: statementImportsApi as jest.Mocked<typeof statementImportsApi>,
};

function stagedRow(description: string): StagedRow {
  return {
    date: '2026-07-10',
    description,
    amount: 45,
    type: 'EXPENSE',
    suggestedCategory: 'Transport',
    categorySource: 'rule',
    ruleId: null,
    likelyDuplicate: false,
    referenceNumber: null,
    balanceAfter: null,
    duplicateMatch: null,
    rowPosition: null,
  };
}

// Every field a fresh render actually touches (detected?.suggestedName etc., all optional-chained)
// is happy with an empty object -- nothing in this file's flow reaches matchExistingAccount, the one
// reader that needs `.bank`.
const detected = {} as DetectedAccountInfo;

function reimportParams(statementImportId: string, nonce: number) {
  return {
    reimport: {
      statementImportId,
      accountId: 'acct-1',
      accountName: 'HDFC Savings',
      staging: {
        rows: [stagedRow(`row for ${statementImportId}`)],
        totalParsed: 1,
        flaggedDuplicates: 0,
        detectedAccount: detected,
        unparseableRows: [],
      },
      nonce,
    },
  };
}

function tree() {
  return (
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })}>
      <ImportScreen />
    </QueryClientProvider>
  );
}

async function settle() {
  await act(async () => {});
}

async function pressImport() {
  fireEvent.press(await screen.findByText(/^Import \d+ transaction/));
  await settle();
}

describe('ImportScreen — re-import confirm attempt key', () => {
  beforeEach(() => {
    mockRouteParams = undefined;
    mockNavigate.mockClear();
    api.accounts.list.mockReset().mockResolvedValue([]);
    api.categories.list.mockReset().mockResolvedValue([]);
    api.import.listSessions.mockReset().mockResolvedValue([]);
    api.statements.confirmReimport.mockReset();
  });

  /**
   * B1's idempotency key is meant to identify one confirm ATTEMPT, kept only so a retry of that
   * same attempt is recognised rather than double-imported (confirmReimport's failed-attempt
   * branch deliberately keeps it — see runConfirm's own comment). The bug: the reimport-arrival
   * block reset every other piece of review state on a new nonce but not this ref, and the ref
   * survives a round trip through the Statement History tab because React Navigation keeps tab
   * screens mounted. A second, completely unconfirmed re-import of a DIFFERENT statement then
   * reused the first one's key -- and claimReimportAttempt on the backend
   * (StatementImportService.java) looks a key up by (user, key) alone, with no statementImportId
   * in the lookup, so it answers "already confirmed" for a re-import that was never even attempted.
   *
   * Reproduced here without a real backend by having the mock enforce that same (user-implicit,
   * key)-only uniqueness rule that claimReimportAttempt enforces, and by having the FIRST call
   * both succeed server-side and still reject the promise -- exactly the "commit landed, response
   * lost" case idempotency keys exist for in the first place (ReimportIdempotencyIT's own target
   * scenario), which is what leaves attemptKey.current holding a key the server has already used.
   */
  it('mints a fresh key for a different re-import rather than reusing one from an earlier failed attempt', async () => {
    const claimedKeys = new Set<string>();
    api.statements.confirmReimport.mockImplementation(async (id, payload) => {
      const key = (payload as { idempotencyKey?: string }).idempotencyKey;
      if (key) {
        if (claimedKeys.has(key)) throw Object.assign(new Error('conflict'), { isConflict: true });
        claimedKeys.add(key);
      }
      if (id === 'stmt-1') {
        // The server committed this claim (recorded above) but the client never sees a response.
        throw new Error('response lost in transit');
      }
      return {
        imported: 1, skipped: 0, duplicatesDetected: 0, transfersIdentified: 0, newMerchantsLearned: 0,
        accountsCreated: [], productsCreated: {}, categoriesAssigned: {}, warnings: [],
        account: null, totalCredits: 0, totalDebits: 45, statementOpeningBalance: null,
        statementClosingBalance: null, statementPeriodStart: null, statementPeriodEnd: null,
        importDurationMs: 1, source: 'reimport',
      };
    });

    mockRouteParams = reimportParams('stmt-1', 1);
    const view = render(tree());
    await pressImport();

    // The first (failed, but server-committed) attempt.
    expect(api.statements.confirmReimport).toHaveBeenCalledTimes(1);
    expect(await screen.findByText(/could not complete the import/i)).toBeTruthy();

    // Arriving at a SECOND, unrelated re-import -- the reachable path is a fresh nonce from
    // History, not a remount, since React Navigation keeps this tab's screen mounted.
    mockRouteParams = reimportParams('stmt-2', 2);
    view.rerender(tree());
    await settle();
    await pressImport();

    expect(api.statements.confirmReimport).toHaveBeenCalledTimes(2);
    const [firstCall, secondCall] = api.statements.confirmReimport.mock.calls;
    expect(firstCall[0]).toBe('stmt-1');
    expect(secondCall[0]).toBe('stmt-2');
    expect(secondCall[1].idempotencyKey).toBeTruthy();
    // The actual bug: without the fix this is the SAME key as the first, doomed attempt, and the
    // mock's claimedKeys check rejects it exactly as the real backend would.
    expect(secondCall[1].idempotencyKey).not.toBe(firstCall[1].idempotencyKey);
    expect(await screen.findByText('Import complete')).toBeTruthy();
  });
});

/**
 * Track C/C6: depended on C4's Ledger drill-through filters existing at all -- without them this
 * would land on the whole, unfiltered ledger, no more useful than the Transactions tab a user
 * could already reach on their own.
 */
describe('ImportScreen — "View in Ledger" (Track C/C6)', () => {
  beforeEach(() => {
    mockRouteParams = undefined;
    mockNavigate.mockClear();
    api.accounts.list.mockReset().mockResolvedValue([]);
    api.categories.list.mockReset().mockResolvedValue([]);
    api.import.listSessions.mockReset().mockResolvedValue([]);
    api.statements.confirmReimport.mockReset();
  });

  async function reachSummary(summary: Record<string, unknown>) {
    api.statements.confirmReimport.mockResolvedValue(summary as never);
    mockRouteParams = reimportParams('stmt-1', 1);
    render(tree());
    await pressImport();
    await screen.findByText('Import complete');
  }

  it('filters by the confirmed account and the statement\'s own period', async () => {
    await reachSummary({
      imported: 1, skipped: 0, duplicatesDetected: 0, transfersIdentified: 0, newMerchantsLearned: 0,
      accountsCreated: [], productsCreated: {}, categoriesAssigned: {}, warnings: [],
      account: { id: 'acct-1', name: 'HDFC Savings' }, totalCredits: 0, totalDebits: 45,
      statementOpeningBalance: null, statementClosingBalance: null,
      statementPeriodStart: '2026-07-01', statementPeriodEnd: '2026-07-31',
      importDurationMs: 1, source: 'reimport',
    });

    fireEvent.press(screen.getByText('View in Ledger'));

    expect(mockNavigate).toHaveBeenCalledWith('Transactions', {
      filters: expect.objectContaining({
        accountId: 'acct-1', dateFrom: '2026-07-01', dateTo: '2026-07-31',
        label: 'HDFC Savings · 2026-07-01 to 2026-07-31',
      }),
    });
  });

  // ImportSummary.account is nullable (see the type's own comment) -- must degrade to an
  // unfiltered-by-account Ledger rather than crash reading `.id` off null.
  it('still opens the Ledger when the confirm response carries no account', async () => {
    await reachSummary({
      imported: 1, skipped: 0, duplicatesDetected: 0, transfersIdentified: 0, newMerchantsLearned: 0,
      accountsCreated: [], productsCreated: {}, categoriesAssigned: {}, warnings: [],
      account: null, totalCredits: 0, totalDebits: 45,
      statementOpeningBalance: null, statementClosingBalance: null,
      statementPeriodStart: null, statementPeriodEnd: null,
      importDurationMs: 1, source: 'reimport',
    });

    fireEvent.press(screen.getByText('View in Ledger'));

    expect(mockNavigate).toHaveBeenCalledWith('Transactions', {
      filters: expect.objectContaining({
        accountId: undefined, dateFrom: undefined, dateTo: undefined, label: 'This import',
      }),
    });
  });
});
