import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AskOnceCard } from './AskOnceCard';
import { transactionsApi, categoriesApi } from '../api/endpoints';
import type { Transaction } from '../types';

vi.mock('../api/endpoints', () => ({
  transactionsApi: { needsReview: vi.fn(), updateCategory: vi.fn() },
  categoriesApi: { list: vi.fn(), options: vi.fn(), create: vi.fn() },
}));

function txn(id: string, description: string): Transaction {
  return {
    id, accountId: 'acc-1', categoryId: '', categoryName: 'Uncategorized', date: '2026-07-01',
    description, merchant: '', paymentMethod: '', amount: 100, type: 'EXPENSE', tags: [],
    notes: null, reconciliationStatus: 'OK', recurring: false, needsCategoryReview: true,
    categoryManuallySet: false,
  };
}

function renderCard() {
  const queryClient = new QueryClient();
  return render(
    <QueryClientProvider client={queryClient}>
      <AskOnceCard />
    </QueryClientProvider>
  );
}

describe('AskOnceCard pagination', () => {
  beforeEach(() => {
    vi.mocked(categoriesApi.list).mockResolvedValue([
      { id: 'cat-1', name: 'Food', isSystem: false, icon: 'tag', color: 'gray' },
      { id: 'cat-2', name: 'Transport', isSystem: false, icon: 'tag', color: 'gray' },
    ] as any);
    vi.mocked(categoriesApi.options).mockResolvedValue({ icons: [], colors: [] } as any);
  });

  it('shows only 10 items on the first page when there are more than 10', async () => {
    const items = Array.from({ length: 25 }, (_, i) => txn(`t${i}`, `Transaction ${i}`));
    vi.mocked(transactionsApi.needsReview).mockResolvedValue(items);
    renderCard();

    await waitFor(() => expect(screen.getByText('Transaction 0')).toBeInTheDocument());

    expect(screen.getAllByText(/^Transaction \d+$/)).toHaveLength(10);
    expect(screen.getByText('Showing 1-10 of 25')).toBeInTheDocument();
    expect(screen.getByText('Page 1 of 3')).toBeInTheDocument();
  });

  it('does not show pagination controls when there are 10 or fewer items', async () => {
    const items = Array.from({ length: 5 }, (_, i) => txn(`t${i}`, `Transaction ${i}`));
    vi.mocked(transactionsApi.needsReview).mockResolvedValue(items);
    renderCard();

    await waitFor(() => expect(screen.getByText('Transaction 0')).toBeInTheDocument());

    expect(screen.queryByText(/Page \d+ of \d+/)).not.toBeInTheDocument();
  });

  it('advances to the next page and shows the correct slice', async () => {
    const user = userEvent.setup();
    const items = Array.from({ length: 25 }, (_, i) => txn(`t${i}`, `Transaction ${i}`));
    vi.mocked(transactionsApi.needsReview).mockResolvedValue(items);
    renderCard();

    await waitFor(() => expect(screen.getByText('Transaction 0')).toBeInTheDocument());
    await user.click(screen.getByLabelText('Next page'));

    expect(screen.getByText('Transaction 10')).toBeInTheDocument();
    expect(screen.queryByText('Transaction 0')).not.toBeInTheDocument();
    expect(screen.getByText('Showing 11-20 of 25')).toBeInTheDocument();
  });

  it('the Previous button is disabled on the first page and Next is disabled on the last', async () => {
    const user = userEvent.setup();
    const items = Array.from({ length: 15 }, (_, i) => txn(`t${i}`, `Transaction ${i}`));
    vi.mocked(transactionsApi.needsReview).mockResolvedValue(items);
    renderCard();

    await waitFor(() => expect(screen.getByText('Transaction 0')).toBeInTheDocument());
    expect(screen.getByLabelText('Previous page')).toBeDisabled();

    await user.click(screen.getByLabelText('Next page'));
    expect(screen.getByLabelText('Next page')).toBeDisabled();
  });
});

describe('AskOnceCard resolve (optimistic)', () => {
  beforeEach(() => {
    vi.mocked(categoriesApi.list).mockResolvedValue([
      { id: 'cat-1', name: 'Food', isSystem: false, icon: 'tag', color: 'gray' },
      { id: 'cat-2', name: 'Transport', isSystem: false, icon: 'tag', color: 'gray' },
    ] as any);
    vi.mocked(categoriesApi.options).mockResolvedValue({ icons: [], colors: [] } as any);
  });

  it('removes the row immediately on Confirm, before the save request resolves', async () => {
    const user = userEvent.setup();
    vi.mocked(transactionsApi.needsReview).mockResolvedValue([txn('t1', 'Coffee Shop')]);
    // Deliberately never resolved in this test -- proves the row is gone before the request
    // finishes, not just eventually after it. Same "an async throw only creates the rejection
    // when actually invoked" reasoning as this file's other tests -- a Promise that never settles
    // can't trip unhandled-rejection detection either way.
    vi.mocked(transactionsApi.updateCategory).mockReturnValue(new Promise(() => {}));
    renderCard();

    await waitFor(() => expect(screen.getByText('Coffee Shop')).toBeInTheDocument());
    const combobox = screen.getByRole('combobox');
    await user.type(combobox, 'Food');
    await user.click(await screen.findByText('Food'));
    await user.click(screen.getByRole('button', { name: /confirm/i }));

    await waitFor(() => expect(screen.queryByText('Coffee Shop')).not.toBeInTheDocument());
  });

  it('restores the row at its original position and shows an error when the save fails', async () => {
    const user = userEvent.setup();
    vi.mocked(transactionsApi.needsReview).mockResolvedValue([
      txn('t1', 'Coffee Shop'), txn('t2', 'Grocery Store'), txn('t3', 'Gas Station'),
    ]);
    vi.mocked(transactionsApi.updateCategory).mockImplementation(async () => {
      throw new Error('Network error');
    });
    renderCard();

    await waitFor(() => expect(screen.getByText('Grocery Store')).toBeInTheDocument());
    const combobox = screen.getAllByRole('combobox')[1];
    await user.type(combobox, 'Food');
    await user.click(await screen.findByText('Food'));
    await user.click(screen.getAllByRole('button', { name: /confirm/i })[1]);

    // The immediate-removal half is covered by the test above (using a promise that never
    // resolves, so the intermediate state is actually observable) -- this test's own job is the
    // END state once the save fails: restored, in its original middle position.
    expect(await screen.findByText('Grocery Store')).toBeInTheDocument();
    expect(screen.getByText("Couldn't save that category — please try again.")).toBeInTheDocument();
    const names = screen.getAllByText(/Coffee Shop|Grocery Store|Gas Station/).map((el) => el.textContent);
    expect(names).toEqual(['Coffee Shop', 'Grocery Store', 'Gas Station']);
  });

  it('keeps its previously chosen category selected after being restored', async () => {
    const user = userEvent.setup();
    vi.mocked(transactionsApi.needsReview).mockResolvedValue([txn('t1', 'Coffee Shop')]);
    vi.mocked(transactionsApi.updateCategory).mockImplementation(async () => {
      throw new Error('Network error');
    });
    renderCard();

    await waitFor(() => expect(screen.getByText('Coffee Shop')).toBeInTheDocument());
    const combobox = screen.getByRole('combobox');
    await user.type(combobox, 'Transport');
    await user.click(await screen.findByText('Transport'));
    await user.click(screen.getByRole('button', { name: /confirm/i }));

    expect(await screen.findByText('Coffee Shop')).toBeInTheDocument();
    expect(screen.getByRole('combobox')).toHaveValue('Transport');
  });
});
