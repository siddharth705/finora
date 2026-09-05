import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { DEFAULT_LEDGER_FILTERS, LEDGER_PAGE_SIZE, LedgerScreen, getLedgerNextPageParam } from './LedgerScreen';
import { categoriesApi, transactionsApi } from '../api/endpoints';
import { hapticImpact } from '../lib/haptics';
import { invalidateFinancialData } from '../lib/invalidateFinancialData';
import type { LedgerDrillThroughFilters } from '../navigation/types';
import type { Transaction } from '../types';

// Controllable stand-in for useRoute, same pattern ImportScreen.test.tsx uses for its own
// reimport-arrival params (Track C/C4).
let mockRouteParams: { filters?: LedgerDrillThroughFilters } | undefined;
jest.mock('@react-navigation/native', () => ({
  useRoute: () => ({ params: mockRouteParams }),
}));

/**
 * Three outcomes of the same request must stay visibly different:
 *
 *   succeeded, no rows  -> "No transactions yet. Import a statement to get started."
 *   succeeded, has rows -> the rows
 *   failed              -> "Couldn't load your transactions." + a retry that re-requests
 *
 * Before this, the first and third were identical. A failed search left `data` undefined, so
 * `txns` was [] and FlatList fell through to ListEmptyComponent -- telling someone who may have
 * years of imported history that they have none, and sending them to re-import data they already
 * own. The empty copy is not neutral: it is an instruction to go and fix a problem that does not
 * exist.
 *
 * The tests assert the DIFFERENCE between the three, not each in isolation, because the bug was
 * never that one state rendered wrongly -- it was that two states rendered the same.
 */

jest.mock('../api/endpoints', () => ({
  transactionsApi: { search: jest.fn(), remove: jest.fn(), updateCategory: jest.fn(), source: jest.fn() },
  categoriesApi: { list: jest.fn() },
}));

jest.mock('../lib/invalidateFinancialData', () => ({
  invalidateFinancialData: jest.fn(),
}));

jest.mock('../lib/haptics');

const transactions = transactionsApi as jest.Mocked<typeof transactionsApi>;
const categories = categoriesApi as jest.Mocked<typeof categoriesApi>;

function txn(over: Partial<Transaction> = {}): Transaction {
  return {
    id: 't-1',
    date: '2026-07-14',
    description: 'Grocery run',
    merchant: 'Big Bazaar',
    amount: -1250,
    type: 'EXPENSE',
    category: 'Food',
    accountId: 'a-1',
    accountName: 'HDFC Savings',
    needsCategoryReview: false,
    recurring: false,
    categoryManuallySet: false,
    ...over,
  } as Transaction;
}

function page(content: Transaction[], over: Record<string, unknown> = {}) {
  return {
    content,
    page: 0,
    size: 20,
    totalElements: content.length,
    totalPages: content.length ? 1 : 0,
    ...over,
  };
}

function renderScreen() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <LedgerScreen />
    </QueryClientProvider>
  );
}

beforeEach(() => {
  jest.clearAllMocks();
  mockRouteParams = undefined;
  categories.list.mockResolvedValue([
    { id: 'c-1', name: 'Food', isSystem: true },
    { id: 'c-2', name: 'Travel', isSystem: true },
  ] as never);
});

describe('the three outcomes stay distinguishable', () => {
  it('succeeded with no rows: offers the import prompt', async () => {
    transactions.search.mockResolvedValue(page([]) as never);

    renderScreen();

    expect(await screen.findByText(/No transactions yet/i)).toBeTruthy();
    // A genuine zero is not a failure and must never be dressed as one.
    expect(screen.queryByText(/Couldn't load your transactions/i)).toBeNull();
  });

  it('succeeded with rows: shows them', async () => {
    transactions.search.mockResolvedValue(page([txn(), txn({ id: 't-2', description: 'Salary' })]) as never);

    renderScreen();

    expect(await screen.findByText('Grocery run')).toBeTruthy();
    expect(screen.getByText('Salary')).toBeTruthy();
    expect(screen.queryByText(/No transactions yet/i)).toBeNull();
    expect(screen.queryByText(/Couldn't load your transactions/i)).toBeNull();
  });

  it('failed: says so, and never claims the ledger is empty', async () => {
    transactions.search.mockRejectedValue(new Error('Network Error'));

    renderScreen();

    expect(await screen.findByText(/Couldn't load your transactions/i)).toBeTruthy();
    // The regression this file exists for.
    expect(screen.queryByText(/No transactions yet/i)).toBeNull();
    expect(screen.queryByText(/Import a statement to get started/i)).toBeNull();
  });

  it('a failed request and an empty one do not render the same thing', async () => {
    // Renders both in one test so the assertion is the difference itself. A refactor that collapses
    // them fails here even when each branch looks individually reasonable.
    transactions.search.mockResolvedValue(page([]) as never);
    const ok = renderScreen();
    await screen.findByText(/No transactions yet/i);
    const emptyShowedError = screen.queryByText(/Couldn't load your transactions/i) !== null;
    ok.unmount();

    transactions.search.mockRejectedValue(new Error('Network Error'));
    renderScreen();
    await screen.findByText(/Couldn't load your transactions/i);
    const failureShowedEmpty = screen.queryByText(/No transactions yet/i) !== null;

    expect(emptyShowedError).toBe(false);
    expect(failureShowedEmpty).toBe(false);
  });
});

describe('counterparty label', () => {
  it('reads the same stored type two different ways depending on direction', async () => {
    transactions.search.mockResolvedValue(
      page([
        txn({ id: 't-out', description: 'PAID SUNIL', type: 'EXPENSE', counterpartyType: 'PERSON' }),
        txn({ id: 't-in', description: 'GOT FROM SUNIL', type: 'INCOME', counterpartyType: 'PERSON' }),
      ]) as never,
    );

    renderScreen();

    // The counterparty type stored is identical on both rows; the accessibility announcement
    // differs because direction is composed in at render time, never stored. This is the guard
    // against the V123 mistake -- a category literally named "Paid a Person" that turned out to be
    // money RECEIVED on 99 of 434 rows it was applied to.
    await waitFor(() => {
      expect(screen.getByLabelText(/Sent to a person/)).toBeTruthy();
      expect(screen.getByLabelText(/Received from a person/)).toBeTruthy();
    });
  });

  it('says nothing about the counterparty when it is unknown', async () => {
    // Roughly a fifth of real rows, plus everything a server backfill has not reached yet. Padding
    // every row in five with "unknown" would make the announcement slower to listen to for zero
    // information gained.
    transactions.search.mockResolvedValue(
      page([txn({ id: 't-1', description: 'Grocery run', counterpartyType: 'UNKNOWN' })]) as never,
    );

    renderScreen();

    await screen.findByText('Grocery run');
    expect(screen.queryByText(/unknown/i)).toBeNull();
  });
});

describe('retry', () => {
  it('issues a NEW request rather than re-rendering the error', async () => {
    transactions.search.mockRejectedValueOnce(new Error('Network Error'));

    renderScreen();
    await screen.findByText(/Couldn't load your transactions/i);
    expect(transactions.search).toHaveBeenCalledTimes(1);

    transactions.search.mockResolvedValue(page([txn({ description: 'Recovered row' })]) as never);
    fireEvent.press(screen.getByText(/Try again/i));

    // The property that matters: the network was hit again. A button that only re-renders the
    // error state is worse than no button, because it looks like it did something.
    await waitFor(() => expect(transactions.search).toHaveBeenCalledTimes(2));
    expect(await screen.findByText('Recovered row')).toBeTruthy();
    expect(screen.queryByText(/Couldn't load your transactions/i)).toBeNull();
  });
});

describe('a failure while paging', () => {
  it('keeps the rows already on screen instead of blanking the list', async () => {
    // First page succeeds and reports a second page exists; the second fails.
    transactions.search
      .mockResolvedValueOnce(page([txn({ description: 'First page row' })], { totalPages: 2, totalElements: 40 }) as never)
      .mockRejectedValueOnce(new Error('Network Error'));

    renderScreen();
    const list = await screen.findByText('First page row');
    expect(list).toBeTruthy();

    fireEvent(screen.getByTestId('ledger-list'), 'onEndReached');

    await waitFor(() => expect(screen.getByText(/Couldn't load more transactions/i)).toBeTruthy());
    // The whole point of separating this from the empty-state branch: what the user was already
    // reading must survive a failed page.
    expect(screen.getByText('First page row')).toBeTruthy();
    expect(screen.queryByText(/No transactions yet/i)).toBeNull();
  });
});

describe('skeleton loading', () => {
  it('shows skeleton placeholder rows while the first page is loading, not a spinner', async () => {
    let resolveSearch: (value: unknown) => void = () => {};
    transactions.search.mockReturnValue(new Promise((resolve) => { resolveSearch = resolve as typeof resolveSearch; }));

    renderScreen();

    expect(screen.getAllByTestId('skeleton-transaction-row', { hidden: true }).length).toBeGreaterThan(0);
    expect(screen.queryByTestId('ledger-list')).toBeNull();

    await act(async () => resolveSearch(page([])));
  });
});

describe('DEFAULT_LEDGER_FILTERS export (for Dashboard prefetch)', () => {
  it('matches exactly what a fresh mount searches with', async () => {
    transactions.search.mockResolvedValue(page([]) as never);

    renderScreen();

    await screen.findByText(/No transactions yet/i);
    expect(transactions.search).toHaveBeenCalledWith({ ...DEFAULT_LEDGER_FILTERS, page: 0 });
  });

  it('exposes the page size and pagination cursor logic LedgerScreen itself uses', () => {
    expect(LEDGER_PAGE_SIZE).toBe(20);
    expect(DEFAULT_LEDGER_FILTERS).toEqual({ size: 20, sortField: 'date', sortDir: 'desc' });
    expect(getLedgerNextPageParam({ content: [], page: 0, size: 20, totalElements: 40, totalPages: 2 })).toBe(1);
    expect(getLedgerNextPageParam({ content: [], page: 1, size: 20, totalElements: 40, totalPages: 2 })).toBeUndefined();
  });
});

describe('long-press haptic', () => {
  it('acknowledges the long press with an impact haptic before offering to delete', async () => {
    transactions.search.mockResolvedValue(page([txn()]) as never);
    renderScreen();
    await waitFor(() => screen.getByText('Grocery run'));

    fireEvent(screen.getByRole('button', { name: /Grocery run/ }), 'longPress');

    expect(hapticImpact).toHaveBeenCalledTimes(1);
  });
});

describe('the header count', () => {
  it('does not print "0 total" for a search that failed', async () => {
    // totalElements falls back to 0 when there are no pages, so a cold failure asserted a confident
    // zero directly above this screen's own "Couldn't load your transactions." -- contradicting, in
    // the header, the rule the error branch states explicitly.
    transactions.search.mockReset().mockRejectedValue(new Error('500'));

    renderScreen();

    expect(await screen.findByText(/Couldn't load your transactions/i)).toBeTruthy();
    expect(screen.queryByText('0 total')).toBeNull();
  });
});

/**
 * The half of the correction loop the review queue cannot reach.
 *
 * `/transactions/needs-review` only returns transactions the engine KNEW it was unsure about. One
 * it categorized confidently and wrongly never appears there -- so before this the ledger offered
 * no way to fix it at all: long-press-to-delete was the row's only write, which made "delete it
 * and re-enter it by hand" the sole route to correcting a category.
 */
describe('correcting a category from the ledger', () => {
  it('opens the picker on tap, seeded with the category the row has now', async () => {
    transactions.search.mockResolvedValue(page([txn({ categoryName: 'Food' })]) as never);

    renderScreen();
    fireEvent.press(await screen.findByText('Grocery run'));

    // The sheet's own title, not the row's -- proves the picker itself opened.
    expect(await screen.findByText('Change category')).toBeTruthy();
    expect(screen.getByText('Travel')).toBeTruthy();
  });

  it('saves the picked category and refreshes the figures it moves', async () => {
    transactions.search.mockResolvedValue(page([txn()]) as never);
    transactions.updateCategory.mockResolvedValue({} as never);

    renderScreen();
    fireEvent.press(await screen.findByText('Grocery run'));
    fireEvent.press(await screen.findByText('Travel'));

    await waitFor(() => expect(transactions.updateCategory).toHaveBeenCalledWith('t-1', 'Travel'));
    // A category move changes spend-by-category, budget progress and insights -- none of which
    // this screen renders, and all of which would otherwise keep showing pre-edit figures.
    expect(invalidateFinancialData).toHaveBeenCalled();
  });

  it('does not call the API when the picked category is the one already set', async () => {
    transactions.search.mockResolvedValue(page([txn({ categoryName: 'Food' })]) as never);

    renderScreen();
    fireEvent.press(await screen.findByText('Grocery run'));
    fireEvent.press(await screen.findByText('Food'));

    await waitFor(() => expect(screen.queryByText('Change category')).toBeNull());
    expect(transactions.updateCategory).not.toHaveBeenCalled();
  });

  it('says so when the save fails, rather than appearing to have worked', async () => {
    transactions.search.mockResolvedValue(page([txn()]) as never);
    transactions.updateCategory.mockRejectedValue(new Error('nope'));

    renderScreen();
    fireEvent.press(await screen.findByText('Grocery run'));
    fireEvent.press(await screen.findByText('Travel'));

    expect(await screen.findByText(/Could not change this category/i)).toBeTruthy();
  });

  it('does not drop a correction to one row while another row is still saving', async () => {
    // Regression: a global useSingleFlight guard here serialized every save through one ref, so
    // fixing row B while row A's request was in flight silently did nothing at all -- no write, no
    // error, the old category still on screen. Rows are independent actions, not one submit button.
    let releaseFirst: (v: unknown) => void = () => {};
    transactions.search.mockResolvedValue(
      page([txn({ id: 't-1' }), txn({ id: 't-2', description: 'Fuel top-up' })]) as never);
    transactions.updateCategory
      .mockImplementationOnce(() => new Promise((resolve) => { releaseFirst = resolve; }) as never)
      .mockResolvedValueOnce({} as never);

    renderScreen();

    fireEvent.press(await screen.findByText('Grocery run'));
    fireEvent.press(await screen.findByText('Travel'));

    // First request deliberately still hanging.
    fireEvent.press(await screen.findByText('Fuel top-up'));
    fireEvent.press(await screen.findByText('Food'));

    await waitFor(() => expect(transactions.updateCategory).toHaveBeenCalledWith('t-2', 'Food'));

    releaseFirst({});

    // Assert an outcome, not the call count -- that count was already satisfied before the release
    // above, so alone it pinned nothing. Both saves must reach invalidateFinancialData; a guard
    // that dropped the second would leave only one.
    await waitFor(() => expect(invalidateFinancialData).toHaveBeenCalledTimes(2));
  });

  it('still offers delete: tap and long-press stay different actions on the same row', async () => {
    transactions.search.mockResolvedValue(page([txn()]) as never);

    renderScreen();
    fireEvent(await screen.findByText('Grocery run'), 'longPress');

    // The picker must NOT have opened -- a long-press that also fired the tap handler would put a
    // category sheet on top of a delete confirmation.
    expect(screen.queryByText('Change category')).toBeNull();
    expect(hapticImpact).toHaveBeenCalled();
  });
});

/**
 * Track C/C4: a drill-through arriving from a donut legend row, a budget card, an insight/mover
 * row, or a report's category breakdown. This tab stays mounted like every other one, so the
 * nonce/re-arrival tests below matter for the same reason ImportScreen's own reimport-arrival
 * tests do -- a second drill-through must be told apart from the first still sitting in state.
 */
describe('drill-through filters (Track C/C4)', () => {
  function filters(over: Partial<LedgerDrillThroughFilters> = {}): LedgerDrillThroughFilters {
    return { label: 'Dining', nonce: 1, ...over };
  }

  beforeEach(() => {
    transactions.search.mockResolvedValue(page([]) as never);
  });

  it('applies an incoming categoryId directly, with no lookup needed', async () => {
    mockRouteParams = { filters: filters({ categoryId: 'c-9' }) };

    renderScreen();

    await waitFor(() => expect(transactions.search).toHaveBeenCalledWith(
      expect.objectContaining({ categoryId: 'c-9' })
    ));
  });

  // Track C/C6: ImportScreen's "View in Ledger" is the one caller that sets this.
  it('applies an incoming accountId, alongside a category or on its own', async () => {
    mockRouteParams = { filters: filters({ accountId: 'acct-1', categoryId: undefined, label: 'HDFC Savings' }) };

    renderScreen();

    await waitFor(() => expect(transactions.search).toHaveBeenCalledWith(
      expect.objectContaining({ accountId: 'acct-1' })
    ));
  });

  it('resolves a categoryName against the category list already fetched for the picker', async () => {
    mockRouteParams = { filters: filters({ categoryName: 'Travel' }) };

    renderScreen();

    await waitFor(() => expect(transactions.search).toHaveBeenCalledWith(
      expect.objectContaining({ categoryId: 'c-2' })
    ));
  });

  // A category renamed or deleted since the caller last saw it -- degrades to no category filter
  // rather than a search built from an id that doesn't exist, which could never match anything.
  it('drops the category filter rather than search for an unresolvable name', async () => {
    mockRouteParams = { filters: filters({ categoryName: 'Nonexistent', dateFrom: '2026-08-01', dateTo: '2026-08-31' }) };

    renderScreen();

    await waitFor(() => expect(transactions.search).toHaveBeenCalledWith(
      expect.objectContaining({ categoryId: undefined, dateFrom: '2026-08-01', dateTo: '2026-08-31' })
    ));
  });

  it('applies the date range and shows the active filter so the result is not a silent mystery', async () => {
    mockRouteParams = { filters: filters({ categoryId: 'c-1', dateFrom: '2026-08-01', dateTo: '2026-08-31', label: 'Food · August 2026' }) };

    renderScreen();

    await waitFor(() => expect(transactions.search).toHaveBeenCalledWith(
      expect.objectContaining({ categoryId: 'c-1', dateFrom: '2026-08-01', dateTo: '2026-08-31' })
    ));
    expect(await screen.findByText('Food · August 2026')).toBeTruthy();
  });

  it('clears the filter on request and searches again without it', async () => {
    mockRouteParams = { filters: filters({ categoryId: 'c-1', label: 'Food' }) };
    renderScreen();
    await screen.findByText('Food');
    transactions.search.mockClear();

    fireEvent.press(screen.getByLabelText('Clear filter: Food'));

    expect(screen.queryByText('Food')).toBeNull();
    await waitFor(() => expect(transactions.search).toHaveBeenCalledWith(
      expect.objectContaining({ categoryId: undefined })
    ));
  });

  // The tab stays mounted (React Navigation's default), so its local state survives a visit to
  // History and back -- the nonce is what tells a genuinely new arrival apart from the same old
  // params still sitting in route.params.filters.
  it('recognises a second drill-through even though the tab never unmounted between the two', async () => {
    mockRouteParams = { filters: filters({ categoryId: 'c-1', label: 'Food', nonce: 1 }) };
    const view = renderScreen();
    await screen.findByText('Food');

    mockRouteParams = { filters: filters({ categoryId: 'c-2', label: 'Travel', nonce: 2 }) };
    view.rerender(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })}>
        <LedgerScreen />
      </QueryClientProvider>
    );

    expect(await screen.findByText('Travel')).toBeTruthy();
    expect(screen.queryByText('Food')).toBeNull();
  });
});

describe('"Where this came from" panel (Track C/C7)', () => {
  it('opens the source panel for the tapped row without also opening the category picker', async () => {
    transactions.search.mockResolvedValue(page([txn()]) as never);
    transactions.source.mockResolvedValue({
      available: true, sourceLabel: 'CSV_IMPORT', statementImportId: 'si-1',
      fileName: 'march-statement.pdf', rowPosition: 14, importedAt: '2026-08-15T10:00:00Z',
      accountName: 'HDFC Savings', statementPeriodStart: '2026-03-01', statementPeriodEnd: '2026-03-31',
    } as never);

    renderScreen();
    fireEvent.press(await screen.findByLabelText('Where this came from'));

    expect(await screen.findByText('march-statement.pdf')).toBeTruthy();
    // Tapping the info button must not also trigger the row's own onPress (category picker).
    expect(screen.queryByText('Change category')).toBeNull();
    expect(transactions.source).toHaveBeenCalledWith('t-1');
  });

  it('closes without affecting the row underneath', async () => {
    transactions.search.mockResolvedValue(page([txn()]) as never);
    transactions.source.mockResolvedValue({
      available: false, sourceLabel: 'MANUAL', statementImportId: null, fileName: null,
      rowPosition: null, importedAt: null, accountName: null,
      statementPeriodStart: null, statementPeriodEnd: null,
    } as never);

    renderScreen();
    fireEvent.press(await screen.findByLabelText('Where this came from'));
    expect(await screen.findByText('You entered this transaction yourself.')).toBeTruthy();

    fireEvent.press(screen.getByText('Close'));

    await waitFor(() => expect(screen.queryByText('You entered this transaction yourself.')).toBeNull());
    expect(screen.getByText('Grocery run')).toBeTruthy();
  });
});
