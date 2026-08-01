import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AskOnceCard } from './AskOnceCard';
import { transactionsApi, categoriesApi } from '../api/endpoints';
import type { Transaction } from '../types';

vi.mock('../api/endpoints', () => ({
  transactionsApi: { needsReview: vi.fn(), updateCategory: vi.fn() },
  categoriesApi: { list: vi.fn() },
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
    vi.mocked(categoriesApi.list).mockResolvedValue([{ name: 'Food' }, { name: 'Transport' }] as any);
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
