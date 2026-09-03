import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { CounterpartyGroupReviewCard } from './CounterpartyGroupReviewCard';
import { transactionsApi, categoriesApi } from '../api/endpoints';
import type { CounterpartyGroup } from '../types';

vi.mock('../api/endpoints', () => ({
  transactionsApi: { groupsNeedsReviewByCounterparty: vi.fn(), bulkRecategorize: vi.fn() },
  categoriesApi: { list: vi.fn(), options: vi.fn(), create: vi.fn() },
}));

function group(over: Partial<CounterpartyGroup> = {}, count = 2): CounterpartyGroup {
  const key = over.counterpartyKey ?? 'vpa:sunilverma';
  const ids = Array.from({ length: count }, (_, i) => `${key}-${i}`);
  return {
    counterpartyKey: key,
    counterpartyType: 'PERSON',
    identityIsStrong: true,
    label: 'UPI to Sunil',
    totalValue: 100 * count,
    transactionIds: ids,
    transactions: ids.map((id, i) => ({
      id, date: `2026-08-${String(10 + i).padStart(2, '0')}`,
      description: `transfer ${i + 1}`, amount: 100 * (i + 1), type: 'EXPENSE' as const,
    })),
    ...over,
  };
}

function renderCard() {
  const queryClient = new QueryClient();
  return render(
    <QueryClientProvider client={queryClient}>
      <CounterpartyGroupReviewCard />
    </QueryClientProvider>
  );
}

describe('CounterpartyGroupReviewCard', () => {
  beforeEach(() => {
    vi.mocked(categoriesApi.list).mockResolvedValue([
      { id: 'cat-1', name: 'Personal Transfer', isSystem: true, icon: 'tag', color: 'gray' },
      { id: 'cat-2', name: 'Food', isSystem: false, icon: 'tag', color: 'gray' },
    ] as any);
    vi.mocked(categoriesApi.options).mockResolvedValue({ icons: [], colors: [] } as any);
  });

  it('renders nothing when there are no groups', async () => {
    vi.mocked(transactionsApi.groupsNeedsReviewByCounterparty).mockResolvedValue([]);
    const { container } = renderCard();

    await waitFor(() => expect(transactionsApi.groupsNeedsReviewByCounterparty).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });

  it('shows each group with its total value and transaction count, sorted as the API returned them', async () => {
    // Sort order is the backend's job (by summed value) -- this card renders whatever order the
    // response arrives in, it doesn't re-sort client-side.
    vi.mocked(transactionsApi.groupsNeedsReviewByCounterparty).mockResolvedValue([
      group({ counterpartyKey: 'vpa:big', label: 'Big Payee', totalValue: 40000 }, 2),
      group({ counterpartyKey: 'vpa:small', label: 'Small Payee', totalValue: 150 }, 3),
    ]);
    renderCard();

    expect(await screen.findByText('Big Payee')).toBeInTheDocument();
    expect(screen.getByText('₹40,000 · 2 transactions')).toBeInTheDocument();
    expect(screen.getByText('Small Payee')).toBeInTheDocument();
    expect(screen.getByText('₹150 · 3 transactions')).toBeInTheDocument();
  });

  it('labels a PERSON and a BUSINESS group with the right type badge, and neither with a direction', async () => {
    vi.mocked(transactionsApi.groupsNeedsReviewByCounterparty).mockResolvedValue([
      group({ counterpartyKey: 'vpa:p', label: 'A Person', counterpartyType: 'PERSON' }),
      group({ counterpartyKey: 'vpa:b', label: 'A Business', counterpartyType: 'BUSINESS' }),
    ]);
    renderCard();

    await screen.findByText('A Person');
    expect(screen.getByText('Person')).toBeInTheDocument();
    expect(screen.getByText('Business')).toBeInTheDocument();
    // No "sent to"/"paid"/"received from" copy -- a group can hold both directions.
    expect(screen.queryByText(/sent to|received from|paid a/i)).not.toBeInTheDocument();
  });

  it('marks a name:-backed (weak) group as Probable, and a vpa:-backed (strong) group not at all', async () => {
    vi.mocked(transactionsApi.groupsNeedsReviewByCounterparty).mockResolvedValue([
      group({ counterpartyKey: 'vpa:strong', label: 'Strong One', identityIsStrong: true }),
      group({ counterpartyKey: 'name:weak', label: 'Weak One', identityIsStrong: false }),
    ]);
    renderCard();

    await screen.findByText('Strong One');
    expect(screen.getAllByText('Probable')).toHaveLength(1);
  });

  it('bulk-applies the chosen category to every transaction in the group', async () => {
    vi.mocked(transactionsApi.groupsNeedsReviewByCounterparty).mockResolvedValue([group({}, 2)]);
    vi.mocked(transactionsApi.bulkRecategorize).mockResolvedValue(undefined as never);
    const user = userEvent.setup();
    renderCard();

    await screen.findByText('UPI to Sunil');
    const combobox = screen.getByRole('combobox');
    await user.click(combobox);
    await user.click(await screen.findByText('Personal Transfer'));
    await user.click(screen.getByRole('button', { name: /apply to 2 transactions/i }));

    await waitFor(() =>
      expect(transactionsApi.bulkRecategorize).toHaveBeenCalledWith(
        ['vpa:sunilverma-0', 'vpa:sunilverma-1'], 'Personal Transfer'));
  });

  it('does not pre-fill or highlight any category by default, even for a PERSON group', async () => {
    // Explicit product decision: no nudge toward "Personal Transfer" -- free-text, same as the
    // merchant card, to avoid repeating the V123 mistake of an over-confident default.
    vi.mocked(transactionsApi.groupsNeedsReviewByCounterparty).mockResolvedValue([
      group({ counterpartyType: 'PERSON' }),
    ]);
    renderCard();

    await screen.findByText('UPI to Sunil');
    expect(screen.getByRole('combobox')).toHaveTextContent(/choose category/i);
  });

  it('removes the group from the list once applied', async () => {
    vi.mocked(transactionsApi.groupsNeedsReviewByCounterparty).mockResolvedValue([group({}, 2)]);
    vi.mocked(transactionsApi.bulkRecategorize).mockResolvedValue(undefined as never);
    const user = userEvent.setup();
    renderCard();

    await screen.findByText('UPI to Sunil');
    await user.click(screen.getByRole('combobox'));
    await user.click(await screen.findByText('Personal Transfer'));
    await user.click(screen.getByRole('button', { name: /apply to 2 transactions/i }));

    await waitFor(() => expect(screen.queryByText('UPI to Sunil')).not.toBeInTheDocument());
  });

  it('keeps each group\'s category pick independent of the others', async () => {
    vi.mocked(transactionsApi.groupsNeedsReviewByCounterparty).mockResolvedValue([
      group({ counterpartyKey: 'vpa:a', label: 'Payee A' }),
      group({ counterpartyKey: 'vpa:b', label: 'Payee B' }, 3),
    ]);
    const user = userEvent.setup();
    renderCard();

    await screen.findByText('Payee A');
    await screen.findByText('Payee B');
    const comboboxes = screen.getAllByRole('combobox');
    expect(comboboxes).toHaveLength(2);

    await user.click(comboboxes[0]);
    await user.click(await screen.findByText('Food'));

    expect(screen.getAllByRole('combobox')[0]).toHaveTextContent('Food');
    expect(screen.getAllByRole('combobox')[1]).toHaveTextContent(/choose category/i);
  });
});

describe('CounterpartyGroupReviewCard — expand to preview', () => {
  beforeEach(() => {
    vi.mocked(categoriesApi.list).mockResolvedValue([
      { id: 'cat-1', name: 'Personal Transfer', isSystem: true, icon: 'tag', color: 'gray' },
    ] as any);
    vi.mocked(categoriesApi.options).mockResolvedValue({ icons: [], colors: [] } as any);
  });

  it('does not show any transaction rows until expanded', async () => {
    vi.mocked(transactionsApi.groupsNeedsReviewByCounterparty).mockResolvedValue([group({}, 2)]);
    renderCard();

    await screen.findByText('UPI to Sunil');

    expect(screen.queryByText('transfer 1')).not.toBeInTheDocument();
  });

  it('shows each transaction in the group once expanded', async () => {
    const user = userEvent.setup();
    vi.mocked(transactionsApi.groupsNeedsReviewByCounterparty).mockResolvedValue([group({}, 2)]);
    renderCard();

    await screen.findByText('UPI to Sunil');
    await user.click(screen.getByRole('button', { name: /show the 2 transactions for upi to sunil/i }));

    expect(await screen.findByText('transfer 1')).toBeInTheDocument();
    expect(screen.getByText('transfer 2')).toBeInTheDocument();
    expect(screen.getByText('-₹100')).toBeInTheDocument();
    expect(screen.getByText('-₹200')).toBeInTheDocument();
  });

  it('still shows credits with the same sign convention as the Ledger table', async () => {
    const user = userEvent.setup();
    vi.mocked(transactionsApi.groupsNeedsReviewByCounterparty).mockResolvedValue([
      group({
        counterpartyKey: 'vpa:refund', label: 'Refund payer', totalValue: 500,
        transactionIds: ['r-0', 'r-1'],
        transactions: [
          { id: 'r-0', date: '2026-08-10', description: 'Refund received', amount: 500, type: 'INCOME' },
          { id: 'r-1', date: '2026-08-11', description: 'Refund received again', amount: 500, type: 'INCOME' },
        ],
      }),
    ]);
    renderCard();

    await screen.findByText('Refund payer');
    await user.click(screen.getByRole('button', { name: /show the 2 transactions for refund payer/i }));

    const amounts = await screen.findAllByText('+₹500');
    expect(amounts).toHaveLength(2);
    amounts.forEach((el) => expect(el).toHaveClass('text-success'));
  });
});
