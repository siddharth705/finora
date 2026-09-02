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
  return render(
    <QueryClientProvider client={queryClient}>
      <CategoryReviewScreen />
    </QueryClientProvider>
  );
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
