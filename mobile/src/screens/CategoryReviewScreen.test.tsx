import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { CategoryReviewScreen } from './CategoryReviewScreen';
import { categoriesApi, transactionsApi } from '../api/endpoints';
import { invalidateFinancialData } from '../lib/invalidateFinancialData';
import type { MerchantGroup, Transaction } from '../types';

jest.mock('../api/endpoints', () => ({
  transactionsApi: {
    needsReview: jest.fn(),
    needsReviewGroups: jest.fn(),
    updateCategory: jest.fn(),
    bulkRecategorize: jest.fn(),
  },
  categoriesApi: { list: jest.fn() },
}));

jest.mock('../lib/invalidateFinancialData', () => ({
  invalidateFinancialData: jest.fn(),
}));

jest.mock('../lib/haptics');

jest.mock('react-native-safe-area-context', () => ({
  useSafeAreaInsets: () => ({ top: 0, bottom: 0, left: 0, right: 0 }),
}));

const transactions = transactionsApi as jest.Mocked<typeof transactionsApi>;
const categories = categoriesApi as jest.Mocked<typeof categoriesApi>;

function txn(over: Partial<Transaction> = {}): Transaction {
  return {
    id: 't-1',
    accountId: 'a-1',
    categoryId: 'c-0',
    categoryName: 'Other',
    date: '2026-08-02',
    description: 'IMPS transfer to a person',
    merchant: 'Unknown',
    paymentMethod: 'UPI',
    amount: -900,
    type: 'EXPENSE',
    tags: [],
    notes: null,
    reconciliationStatus: 'OK',
    recurring: false,
    needsCategoryReview: true,
    categoryManuallySet: false,
    ...over,
  };
}

function group(over: Partial<MerchantGroup> = {}): MerchantGroup {
  return { merchantId: 'm-1', merchantName: 'Swiggy', transactionIds: ['t-9', 't-8', 't-7'], ...over };
}

function renderScreen() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <CategoryReviewScreen />
    </QueryClientProvider>
  );
  // Returned so a test can trigger the refetch that invalidateFinancialData performs in
  // production -- it is mocked out here, so without this the post-save refetch never runs and the
  // race it used to cause is invisible.
  return { ...utils, queryClient };
}

beforeEach(() => {
  jest.clearAllMocks();
  categories.list.mockResolvedValue([
    { id: 'c-1', name: 'Food', isSystem: true },
    { id: 'c-2', name: 'Travel', isSystem: true },
  ] as never);
  transactions.needsReview.mockResolvedValue([]);
  transactions.needsReviewGroups.mockResolvedValue([]);
});

describe('rendering both halves of the backlog', () => {
  /**
   * The two server queries are disjoint -- TransactionService.needsReview filters out everything
   * TransactionGroupingService already returned. Rendering only one of them silently strands the
   * other half of the user's backlog with no way to reach it.
   */
  it('shows merchant groups and one-off transactions together', async () => {
    transactions.needsReviewGroups.mockResolvedValue([group()]);
    transactions.needsReview.mockResolvedValue([txn()]);

    renderScreen();

    expect(await screen.findByText('Swiggy')).toBeTruthy();
    expect(screen.getByText('3 transactions')).toBeTruthy();
    expect(screen.getByText('IMPS transfer to a person')).toBeTruthy();
  });

  it('says the queue is clear rather than rendering an empty screen', async () => {
    renderScreen();
    expect(await screen.findByText(/Nothing to review/i)).toBeTruthy();
  });

  it('never claims the queue is clear when half of it failed to load', async () => {
    // The trap: singles succeed and return nothing while groups error. "No rows, and not a total
    // failure" reads as an empty queue, so the screen would congratulate the user for finishing
    // work it simply could not see -- a failure wearing a success's clothes, which is exactly the
    // conflation LedgerScreen.test.tsx pins from the other direction.
    transactions.needsReview.mockResolvedValue([]);
    transactions.needsReviewGroups.mockRejectedValue(new Error('down'));

    renderScreen();

    expect(await screen.findByText(/Part of your review queue couldn’t be loaded/i)).toBeTruthy();
    expect(screen.queryByText(/Nothing to review/i)).toBeNull();
  });

  it('flags the missing half even while the loaded half renders rows', async () => {
    transactions.needsReviewGroups.mockRejectedValue(new Error('down'));
    transactions.needsReview.mockResolvedValue([txn()]);

    renderScreen();

    expect(await screen.findByText('IMPS transfer to a person')).toBeTruthy();
    expect(screen.getByText(/Part of your review queue couldn’t be loaded/i)).toBeTruthy();
  });

  it('keeps the half that loaded when only the other half fails', async () => {
    // A partial failure must not blank the screen: the rows that did load are fully actionable,
    // and hiding them would strand work the user could have done.
    transactions.needsReviewGroups.mockRejectedValue(new Error('down'));
    transactions.needsReview.mockResolvedValue([txn()]);

    renderScreen();

    expect(await screen.findByText('IMPS transfer to a person')).toBeTruthy();
    expect(screen.queryByText(/Couldn’t load your review queue/i)).toBeNull();
  });

  it('reports a total failure instead of claiming the queue is empty', async () => {
    transactions.needsReview.mockRejectedValue(new Error('down'));
    transactions.needsReviewGroups.mockRejectedValue(new Error('down'));

    renderScreen();

    expect(await screen.findByText(/Couldn’t load your review queue/i)).toBeTruthy();
    // A failed load is not a clear queue, and must never congratulate the user for one.
    expect(screen.queryByText(/Nothing to review/i)).toBeNull();
  });
});

describe('resolving a one-off transaction', () => {
  it('applies the picked category and clears the row', async () => {
    transactions.needsReview.mockResolvedValue([txn()]);
    transactions.updateCategory.mockResolvedValue({} as never);

    renderScreen();
    fireEvent.press(await screen.findByText('IMPS transfer to a person'));
    fireEvent.press(await screen.findByText('Food'));

    await waitFor(() => expect(transactions.updateCategory).toHaveBeenCalledWith('t-1', 'Food'));
    await waitFor(() => expect(screen.queryByText('IMPS transfer to a person')).toBeNull());
    expect(invalidateFinancialData).toHaveBeenCalled();
  });

  it('puts the row back and explains itself when the save fails', async () => {
    // The optimistic removal is a bet on the happy path. When the bet loses, the row has to come
    // back -- otherwise the user believes they categorized something they did not, and the item
    // silently returns to the queue on the next refetch with no explanation.
    transactions.needsReview.mockResolvedValue([txn()]);
    transactions.updateCategory.mockRejectedValue(new Error('nope'));

    renderScreen();
    fireEvent.press(await screen.findByText('IMPS transfer to a person'));
    fireEvent.press(await screen.findByText('Food'));

    expect(await screen.findByText(/Could not save that category/i)).toBeTruthy();
    expect(screen.getByText('IMPS transfer to a person')).toBeTruthy();
    expect(invalidateFinancialData).not.toHaveBeenCalled();
  });
});

describe('resolving a merchant group', () => {
  it('names the stakes in the picker before anything is applied', async () => {
    // The sheet covers the row that explains what is about to happen, so it has to carry that
    // itself: applying to five transactions is a materially different action from one.
    transactions.needsReviewGroups.mockResolvedValue([group()]);

    renderScreen();
    fireEvent.press(await screen.findByText('Swiggy'));

    expect(await screen.findByText('Apply to 3 transactions')).toBeTruthy();
  });

  it('bulk-applies to every transaction in the group', async () => {
    transactions.needsReviewGroups.mockResolvedValue([group()]);
    transactions.bulkRecategorize.mockResolvedValue({} as never);

    renderScreen();
    fireEvent.press(await screen.findByText('Swiggy'));
    fireEvent.press(await screen.findByText('Food'));

    await waitFor(() =>
      expect(transactions.bulkRecategorize).toHaveBeenCalledWith(['t-9', 't-8', 't-7'], 'Food'));
    await waitFor(() => expect(screen.queryByText('Swiggy')).toBeNull());
  });

  it('restores the group when the bulk apply fails', async () => {
    transactions.needsReviewGroups.mockResolvedValue([group()]);
    transactions.bulkRecategorize.mockRejectedValue(new Error('nope'));

    renderScreen();
    fireEvent.press(await screen.findByText('Swiggy'));
    fireEvent.press(await screen.findByText('Food'));

    expect(await screen.findByText(/Could not apply that category/i)).toBeTruthy();
    expect(screen.getByText('Swiggy')).toBeTruthy();
  });

  it('uses the singular noun for a one-transaction group', async () => {
    // The server filters these out today (MIN_GROUP_SIZE is 2), so this pins the wording against
    // that threshold ever changing rather than describing what the API returns now.
    transactions.needsReviewGroups.mockResolvedValue([group({ transactionIds: ['t-9'] })]);

    renderScreen();
    fireEvent.press(await screen.findByText('Swiggy'));

    expect(await screen.findByText('Apply to 1 transaction')).toBeTruthy();
  });
});

/**
 * Regression: a global single-flight guard here dropped corrections on the floor.
 *
 * Every other write screen in this app funnels its saves through useSingleFlight, which serializes
 * on one ref -- correct where a screen has one submit button and a second press means the same
 * save twice. This screen is the opposite shape: each row is a distinct action, and this queue
 * exists to be worked through fast. Serializing meant that on a slow connection, resolving row B
 * while row A was still in flight silently did nothing at all.
 */
describe('corrections to different rows do not block each other', () => {
  it('applies a second row while the first is still in flight', async () => {
    let releaseFirst: (v: unknown) => void = () => {};
    transactions.needsReview.mockResolvedValue([
      txn({ id: 't-1', description: 'First row' }),
      txn({ id: 't-2', description: 'Second row' }),
    ]);
    transactions.updateCategory
      .mockImplementationOnce(() => new Promise((resolve) => { releaseFirst = resolve; }) as never)
      .mockResolvedValueOnce({} as never);

    renderScreen();

    fireEvent.press(await screen.findByText('First row'));
    fireEvent.press(await screen.findByText('Food'));

    // First request is deliberately still hanging here.
    fireEvent.press(await screen.findByText('Second row'));
    fireEvent.press(await screen.findByText('Travel'));

    await waitFor(() => expect(transactions.updateCategory).toHaveBeenCalledWith('t-2', 'Travel'));

    releaseFirst({});
    await waitFor(() => expect(transactions.updateCategory).toHaveBeenCalledTimes(2));
  });
});

/**
 * Regression: the queue used to live in the TanStack cache, and every successful save invalidated
 * the very keys holding it. Both bugs below shipped in the original A4 commit.
 */
describe('the queue survives its own refetches', () => {
  it('keeps a resolved row hidden when a refetch still reports it as needing review', async () => {
    // The race: resolve A, resolve B before A lands, A succeeds and invalidates. The refetch runs
    // while B's write is still in flight, so the server still lists BOTH -- and the old
    // cache-mutating version let that response put the resolved row back on screen, where the user
    // would answer it a second time.
    //
    // The refetch deliberately returns an EXTRA row that was not in the first response. Waiting for
    // that row to appear is what proves the refetch actually landed and re-rendered; without it the
    // assertion below races React's flush and passes even against the broken implementation.
    transactions.needsReview
      .mockResolvedValueOnce([
        txn({ id: 't-1', description: 'First row' }),
        txn({ id: 't-2', description: 'Second row' }),
      ])
      .mockResolvedValue([
        txn({ id: 't-1', description: 'First row' }),
        txn({ id: 't-2', description: 'Second row' }),
        txn({ id: 't-3', description: 'Late arrival' }),
      ]);
    transactions.updateCategory.mockResolvedValue({} as never);

    const { queryClient } = renderScreen();

    fireEvent.press(await screen.findByText('First row'));
    fireEvent.press(await screen.findByText('Food'));
    await waitFor(() => expect(screen.queryByText('First row')).toBeNull());

    // Server has not caught up: needs-review still reports the row just resolved.
    await queryClient.refetchQueries({ queryKey: ['needs-review'] });
    expect(await screen.findByText('Late arrival')).toBeTruthy();   // refetch has rendered

    expect(screen.queryByText('First row')).toBeNull();
    expect(screen.getByText('Second row')).toBeTruthy();
  });

  it('does not blank a populated queue when a background refetch fails', async () => {
    // TanStack v5 keeps data and flips status to 'error' when a BACKGROUND refetch fails. Deriving
    // the failure card from isError alone therefore threw away a full, still-actionable queue on
    // the first blip -- losing the user's place partway through it. LedgerScreen guards the same
    // case with `isError && txns.length === 0`; this screen shipped without the second half.
    transactions.needsReview
      .mockResolvedValueOnce([txn({ description: 'Still actionable' })])
      .mockRejectedValue(new Error('blip'));
    transactions.needsReviewGroups
      .mockResolvedValueOnce([group()])
      .mockRejectedValue(new Error('blip'));

    const { queryClient } = renderScreen();
    expect(await screen.findByText('Still actionable')).toBeTruthy();

    await queryClient.refetchQueries().catch(() => {});

    // Wait on a signal only the CORRECT behaviour produces: an errored-but-populated queue is the
    // partial-failure banner, not the failure card. Waiting on cache status instead would race
    // React's flush and pass against the broken version, which renders the card and drops the
    // rows entirely.
    expect(await screen.findByText(/Part of your review queue couldn’t be loaded/i)).toBeTruthy();

    expect(screen.getByText('Still actionable')).toBeTruthy();
    expect(screen.getByText('Swiggy')).toBeTruthy();
    expect(screen.queryByText(/Couldn’t load your review queue/i)).toBeNull();
  });

  it('rolls a failed save back to the row’s original position', async () => {
    // Un-hiding restores position for free; the old index-based re-insertion had to compute it.
    transactions.needsReview.mockResolvedValue([
      txn({ id: 't-1', description: 'Alpha' }),
      txn({ id: 't-2', description: 'Beta' }),
      txn({ id: 't-3', description: 'Gamma' }),
    ]);
    transactions.updateCategory.mockRejectedValue(new Error('nope'));

    renderScreen();
    fireEvent.press(await screen.findByText('Beta'));
    fireEvent.press(await screen.findByText('Food'));

    expect(await screen.findByText(/Could not save that category/i)).toBeTruthy();
    const rows = screen.getAllByText(/Alpha|Beta|Gamma/).map((n) => n.props.children);
    expect(rows).toEqual(['Alpha', 'Beta', 'Gamma']);
  });
});

describe('recovering from a failed category list', () => {
  it('refetches categories on Try again, not just the two queue halves', async () => {
    // The picker draws its options from ['categories']. Leaving that query out of the recovery
    // path meant a failed category fetch left every row opening an empty sheet, permanently, with
    // Try again unable to fix it.
    transactions.needsReview.mockRejectedValue(new Error('down'));
    transactions.needsReviewGroups.mockRejectedValue(new Error('down'));
    categories.list.mockRejectedValueOnce(new Error('down'));

    renderScreen();
    fireEvent.press(await screen.findByText(/Try again/i));

    await waitFor(() => expect(categories.list).toHaveBeenCalledTimes(2));
  });
});
