import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MerchantGroupReviewCard } from './MerchantGroupReviewCard';
import { transactionsApi, categoriesApi } from '../api/endpoints';
import type { MerchantGroup } from '../types';

vi.mock('../api/endpoints', () => ({
  transactionsApi: { groupsNeedsReview: vi.fn(), bulkRecategorize: vi.fn() },
  categoriesApi: { list: vi.fn(), options: vi.fn(), create: vi.fn() },
}));

function group(merchantName: string, count: number): MerchantGroup {
  const ids = Array.from({ length: count }, (_, i) => `${merchantName}-${i}`);
  return {
    merchantId: `m-${merchantName}`,
    merchantName,
    transactionIds: ids,
    transactions: ids.map((id, i) => ({
      id, date: `2026-08-${String(10 + i).padStart(2, '0')}`,
      description: `${merchantName} purchase ${i + 1}`, amount: 100 * (i + 1), type: 'EXPENSE' as const,
    })),
  };
}

function renderCard() {
  const queryClient = new QueryClient();
  return render(
    <QueryClientProvider client={queryClient}>
      <MerchantGroupReviewCard />
    </QueryClientProvider>
  );
}

describe('MerchantGroupReviewCard', () => {
  beforeEach(() => {
    vi.mocked(categoriesApi.list).mockResolvedValue([
      { id: 'cat-1', name: 'Food', isSystem: false, icon: 'tag', color: 'gray' },
      { id: 'cat-2', name: 'Transport', isSystem: false, icon: 'tag', color: 'gray' },
    ] as any);
    vi.mocked(categoriesApi.options).mockResolvedValue({ icons: [], colors: [] } as any);
  });

  it('renders nothing when there are no groups', async () => {
    vi.mocked(transactionsApi.groupsNeedsReview).mockResolvedValue([]);
    const { container } = renderCard();

    await waitFor(() => expect(transactionsApi.groupsNeedsReview).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });

  it('shows each merchant group with its transaction count', async () => {
    vi.mocked(transactionsApi.groupsNeedsReview).mockResolvedValue([group('SWIGGY', 5), group('UBER', 3)]);
    renderCard();

    expect(await screen.findByText('SWIGGY')).toBeInTheDocument();
    expect(screen.getByText('5 transactions')).toBeInTheDocument();
    expect(screen.getByText('UBER')).toBeInTheDocument();
    expect(screen.getByText('3 transactions')).toBeInTheDocument();
  });

  it('bulk-applies the chosen category to every transaction in the group', async () => {
    vi.mocked(transactionsApi.groupsNeedsReview).mockResolvedValue([group('SWIGGY', 5)]);
    vi.mocked(transactionsApi.bulkRecategorize).mockResolvedValue(undefined as never);
    const user = userEvent.setup();
    renderCard();

    await screen.findByText('SWIGGY');
    const combobox = screen.getByRole('combobox');
    await user.click(combobox);
    await user.click(await screen.findByText('Food'));
    await user.click(screen.getByRole('button', { name: /apply to 5 transactions/i }));

    await waitFor(() =>
      expect(transactionsApi.bulkRecategorize).toHaveBeenCalledWith(
        ['SWIGGY-0', 'SWIGGY-1', 'SWIGGY-2', 'SWIGGY-3', 'SWIGGY-4'], 'Food'));
  });

  it('removes the group from the list once applied', async () => {
    vi.mocked(transactionsApi.groupsNeedsReview).mockResolvedValue([group('SWIGGY', 5)]);
    vi.mocked(transactionsApi.bulkRecategorize).mockResolvedValue(undefined as never);
    const user = userEvent.setup();
    renderCard();

    await screen.findByText('SWIGGY');
    const combobox = screen.getByRole('combobox');
    await user.click(combobox);
    await user.click(await screen.findByText('Food'));
    await user.click(screen.getByRole('button', { name: /apply to 5 transactions/i }));

    await waitFor(() => expect(screen.queryByText('SWIGGY')).not.toBeInTheDocument());
  });

  it('keeps each group\'s category pick independent of the others', async () => {
    vi.mocked(transactionsApi.groupsNeedsReview).mockResolvedValue([group('SWIGGY', 5), group('UBER', 3)]);
    const user = userEvent.setup();
    renderCard();

    await screen.findByText('SWIGGY');
    await screen.findByText('UBER');
    const comboboxes = screen.getAllByRole('combobox');
    expect(comboboxes).toHaveLength(2);

    await user.click(comboboxes[0]);
    await user.click(await screen.findByText('Food'));

    // Only the SWIGGY row's combobox should reflect the pick -- the UBER row must stay untouched.
    expect(screen.getAllByRole('combobox')[0]).toHaveTextContent('Food');
    expect(screen.getAllByRole('combobox')[1]).toHaveTextContent('Choose category');
    expect(screen.getByRole('button', { name: /apply to 3 transactions/i })).toBeDisabled();
    expect(screen.getByRole('button', { name: /apply to 5 transactions/i })).not.toBeDisabled();
  });
});

/**
 * Reported directly: "user should have a drop down to see all the groups transaction" --
 * applying a category to "5 transactions" sight-unseen asked for trust the card gave no way to
 * check. Each group can now expand into the actual rows it would apply to.
 */
describe('MerchantGroupReviewCard — expand to preview', () => {
  beforeEach(() => {
    vi.mocked(categoriesApi.list).mockResolvedValue([
      { id: 'cat-1', name: 'Food', isSystem: false, icon: 'tag', color: 'gray' },
    ] as any);
    vi.mocked(categoriesApi.options).mockResolvedValue({ icons: [], colors: [] } as any);
  });

  it('does not show any transaction rows until expanded', async () => {
    vi.mocked(transactionsApi.groupsNeedsReview).mockResolvedValue([group('SWIGGY', 2)]);
    renderCard();

    await screen.findByText('SWIGGY');

    expect(screen.queryByText('SWIGGY purchase 1')).not.toBeInTheDocument();
  });

  it('shows each transaction in the group once expanded', async () => {
    const user = userEvent.setup();
    vi.mocked(transactionsApi.groupsNeedsReview).mockResolvedValue([group('SWIGGY', 2)]);
    renderCard();

    await screen.findByText('SWIGGY');
    await user.click(screen.getByRole('button', { name: /show the 2 transactions for swiggy/i }));

    expect(await screen.findByText('SWIGGY purchase 1')).toBeInTheDocument();
    expect(screen.getByText('SWIGGY purchase 2')).toBeInTheDocument();
    expect(screen.getByText('-₹100')).toBeInTheDocument();
    expect(screen.getByText('-₹200')).toBeInTheDocument();
  });

  it('collapses again on a second click', async () => {
    const user = userEvent.setup();
    vi.mocked(transactionsApi.groupsNeedsReview).mockResolvedValue([group('SWIGGY', 2)]);
    renderCard();

    await screen.findByText('SWIGGY');
    const toggle = screen.getByRole('button', { name: /show the 2 transactions for swiggy/i });
    await user.click(toggle);
    await screen.findByText('SWIGGY purchase 1');

    await user.click(screen.getByRole('button', { name: /hide the 2 transactions for swiggy/i }));

    await waitFor(() => expect(screen.queryByText('SWIGGY purchase 1')).not.toBeInTheDocument());
  });

  it('keeps each group\'s expanded state independent of the others', async () => {
    const user = userEvent.setup();
    vi.mocked(transactionsApi.groupsNeedsReview).mockResolvedValue([group('SWIGGY', 2), group('UBER', 2)]);
    renderCard();

    await screen.findByText('SWIGGY');
    await screen.findByText('UBER');
    await user.click(screen.getByRole('button', { name: /show the 2 transactions for swiggy/i }));

    expect(await screen.findByText('SWIGGY purchase 1')).toBeInTheDocument();
    expect(screen.queryByText('UBER purchase 1')).not.toBeInTheDocument();
  });

  it('still lets the transactions preview show credits with the same sign convention as the Ledger table', async () => {
    const user = userEvent.setup();
    vi.mocked(transactionsApi.groupsNeedsReview).mockResolvedValue([
      {
        merchantId: 'm-REFUND', merchantName: 'REFUND CO', transactionIds: ['r-0'],
        transactions: [{ id: 'r-0', date: '2026-08-10', description: 'Refund received', amount: 500, type: 'INCOME' }],
      },
    ] as any);
    renderCard();

    await screen.findByText('REFUND CO');
    await user.click(screen.getByRole('button', { name: /show the 1 transactions for refund co/i }));

    const amount = await screen.findByText('+₹500');
    expect(amount).toHaveClass('text-success');
  });
});
