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
  return {
    merchantId: `m-${merchantName}`,
    merchantName,
    transactionIds: Array.from({ length: count }, (_, i) => `${merchantName}-${i}`),
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
    await user.type(combobox, 'Food');
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
    await user.type(combobox, 'Food');
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

    await user.type(comboboxes[0], 'Food');
    await user.click(await screen.findByText('Food'));

    // Only the SWIGGY row's combobox should reflect the pick -- the UBER row must stay untouched.
    expect(screen.getAllByRole('combobox')[0]).toHaveValue('Food');
    expect(screen.getAllByRole('combobox')[1]).toHaveValue('');
    expect(screen.getByRole('button', { name: /apply to 3 transactions/i })).toBeDisabled();
    expect(screen.getByRole('button', { name: /apply to 5 transactions/i })).not.toBeDisabled();
  });
});
