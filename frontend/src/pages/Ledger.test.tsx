import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Ledger from './Ledger';
import { transactionsApi, categoriesApi } from '../api/endpoints';
import type { Transaction } from '../types';

// Ledger has no prior test file -- this covers only what this change adds (the "Why this
// category?" explanation panel, C6.1's Transaction Explanation panel), not the whole page.
vi.mock('../api/endpoints', () => ({
  transactionsApi: {
    search: vi.fn(),
    needsReview: vi.fn(),
    explanation: vi.fn(),
    remove: vi.fn(),
    update: vi.fn(),
  },
  categoriesApi: { list: vi.fn(), options: vi.fn(), create: vi.fn() },
}));

// Real MerchantGroupReviewCard calls transactionsApi.groupsNeedsReview, which the mock above
// doesn't define -- this file's tests are about the "Why this category?" panel, not the merchant-
// group card, so it's stubbed to a static marker rather than pulled into the shared endpoints mock.
vi.mock('../components/MerchantGroupReviewCard', () => ({
  MerchantGroupReviewCard: () => <div data-testid="merchant-group-review-card" />,
}));

function txn(overrides: Partial<Transaction> = {}): Transaction {
  return {
    id: 'txn-1',
    accountId: 'acc-1',
    categoryId: 'cat-1',
    categoryName: 'Shopping',
    date: '2026-08-15',
    description: 'AMAZON PAY',
    merchant: 'Amazon',
    paymentMethod: 'Card',
    amount: 1299,
    type: 'EXPENSE',
    tags: [],
    notes: null,
    reconciliationStatus: 'OK',
    recurring: false,
    needsCategoryReview: false,
    categoryManuallySet: false,
    ...overrides,
  };
}

function renderLedger() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <Ledger />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('Ledger — Why this category?', () => {
  beforeEach(() => {
    vi.mocked(transactionsApi.search).mockReset().mockResolvedValue({
      content: [txn()], page: 0, size: 10, totalElements: 1, totalPages: 1,
    });
    vi.mocked(transactionsApi.needsReview).mockReset().mockResolvedValue([]);
    vi.mocked(categoriesApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(transactionsApi.explanation).mockReset();
  });

  it('opens the explanation panel and shows the summary and evidence', async () => {
    const user = userEvent.setup();
    vi.mocked(transactionsApi.explanation).mockResolvedValue({
      decisionSource: 'USER_RULE',
      summary: 'Matched a rule you created. description contains "AMAZON" → Shopping.',
      evidence: ['Rule condition: description contains "AMAZON"', 'Assigns category: Shopping'],
    });
    renderLedger();

    await user.click(await screen.findByTitle('Why this category?'));

    expect(await screen.findByText(/matched a rule you created/i)).toBeInTheDocument();
    expect(screen.getByText(/rule condition: description contains "amazon"/i)).toBeInTheDocument();
    expect(transactionsApi.explanation).toHaveBeenCalledWith('txn-1');
  });

  it('shows the confidence percentage when the explanation includes one', async () => {
    const user = userEvent.setup();
    vi.mocked(transactionsApi.explanation).mockResolvedValue({
      decisionSource: 'LEARNED_PATTERN',
      summary: 'Categorized based on how you\'ve categorized "SWIGGY" before.',
      evidence: ['Every time you confirm or correct a category, Fynora remembers it for that merchant.'],
      confidence: 82,
    });
    renderLedger();

    await user.click(await screen.findByTitle('Why this category?'));

    expect(await screen.findByText(/82% confidence/i)).toBeInTheDocument();
  });

  it('shows no confidence line when the explanation has none', async () => {
    const user = userEvent.setup();
    vi.mocked(transactionsApi.explanation).mockResolvedValue({
      decisionSource: 'MANUAL',
      summary: 'You set this category yourself.',
      evidence: [],
    });
    renderLedger();

    await user.click(await screen.findByTitle('Why this category?'));

    await screen.findByText(/you set this category yourself/i);
    expect(screen.queryByText(/% confidence/i)).not.toBeInTheDocument();
  });

  it('shows a plain error when the explanation fails to load', async () => {
    const user = userEvent.setup();
    vi.mocked(transactionsApi.explanation).mockRejectedValue(new Error('network error'));
    renderLedger();

    await user.click(await screen.findByTitle('Why this category?'));

    expect(await screen.findByText(/couldn't load this explanation/i)).toBeInTheDocument();
  });

  it('renders no evidence list when there is none to show', async () => {
    const user = userEvent.setup();
    vi.mocked(transactionsApi.explanation).mockResolvedValue({
      decisionSource: 'MANUAL',
      summary: 'You set this category yourself.',
      evidence: [],
    });
    renderLedger();

    await user.click(await screen.findByTitle('Why this category?'));

    await screen.findByText('You set this category yourself.');
    expect(screen.queryByRole('list')).not.toBeInTheDocument();
  });

  it('closes the panel without leaving a stale fetch dangling', async () => {
    const user = userEvent.setup();
    vi.mocked(transactionsApi.explanation).mockResolvedValue({
      decisionSource: 'MANUAL', summary: 'You set this category yourself.', evidence: [],
    });
    renderLedger();

    await user.click(await screen.findByTitle('Why this category?'));
    await screen.findByText('You set this category yourself.');
    await user.click(screen.getByRole('button', { name: 'Close' }));

    await waitFor(() => expect(screen.queryByText('Why this category?')).not.toBeInTheDocument());
  });
});

// Custom in-app confirmation (ConfirmDialog) rather than the browser's own confirm(), which
// rendered as unstyled OS/browser chrome instead of looking like part of the product.
describe('Ledger — delete confirmation', () => {
  beforeEach(() => {
    vi.mocked(transactionsApi.search).mockReset().mockResolvedValue({
      content: [txn()], page: 0, size: 10, totalElements: 1, totalPages: 1,
    });
    vi.mocked(transactionsApi.needsReview).mockReset().mockResolvedValue([]);
    vi.mocked(categoriesApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(transactionsApi.remove).mockReset().mockResolvedValue(undefined as never);
  });

  it('shows a confirmation naming the transaction before deleting it', async () => {
    const user = userEvent.setup();
    renderLedger();

    await user.click(await screen.findByTitle('Delete transaction'));

    expect(await screen.findByText('Delete "AMAZON PAY"?')).toBeInTheDocument();
    expect(transactionsApi.remove).not.toHaveBeenCalled();

    await user.click(screen.getByRole('button', { name: 'Delete' }));

    await waitFor(() => expect(transactionsApi.remove).toHaveBeenCalledWith('txn-1'));
  });

  it('does not delete when the confirmation is cancelled', async () => {
    const user = userEvent.setup();
    renderLedger();

    await user.click(await screen.findByTitle('Delete transaction'));
    await screen.findByText('Delete "AMAZON PAY"?');
    await user.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(transactionsApi.remove).not.toHaveBeenCalled();
    expect(screen.queryByText('Delete "AMAZON PAY"?')).not.toBeInTheDocument();
  });
});

// Task 11: the edit modal's category field is now CategoryCombobox (Task 8) plus the inline
// CategoryCreateEditPanel (Task 9) for "+ Create", replacing the old plain <select>.
describe('Ledger — edit transaction category picker', () => {
  beforeEach(() => {
    vi.mocked(transactionsApi.search).mockReset().mockResolvedValue({
      content: [txn()], page: 0, size: 10, totalElements: 1, totalPages: 1,
    });
    vi.mocked(transactionsApi.needsReview).mockReset().mockResolvedValue([]);
    vi.mocked(transactionsApi.update).mockReset().mockResolvedValue(txn());
    vi.mocked(categoriesApi.list).mockReset().mockResolvedValue([
      { id: 'cat-1', name: 'Shopping', isSystem: false, icon: 'tag', color: 'gray' },
      { id: 'cat-2', name: 'Groceries', isSystem: false, icon: 'tag', color: 'gray' },
    ]);
    vi.mocked(categoriesApi.options).mockReset().mockResolvedValue({ icons: [], colors: [] });
    vi.mocked(categoriesApi.create).mockReset();
  });

  it('lets an existing category be picked from the combobox and saved', async () => {
    const user = userEvent.setup();
    renderLedger();

    await user.click(await screen.findByTitle('Edit transaction'));
    const combobox = (await screen.findAllByRole('combobox')).find((el) => el.tagName === 'BUTTON')!;
    expect(combobox).toHaveTextContent('Shopping');

    await user.click(combobox);
    await user.type(await screen.findByPlaceholderText('Search categories'), 'Groceries');
    await user.click(await screen.findByText('Groceries'));
    expect(combobox).toHaveTextContent('Groceries');

    await user.click(screen.getByRole('button', { name: /save changes/i }));

    await waitFor(() => expect(transactionsApi.update).toHaveBeenCalledWith(
      'txn-1',
      expect.objectContaining({ categoryName: 'Groceries' }),
    ));
  });

  it('opens the inline create panel for a brand-new category name and selects it once saved', async () => {
    const user = userEvent.setup();
    vi.mocked(categoriesApi.create).mockResolvedValue({
      id: 'cat-3', name: 'Travel', isSystem: false, icon: 'tag', color: 'gray',
    });
    renderLedger();

    await user.click(await screen.findByTitle('Edit transaction'));
    const combobox = (await screen.findAllByRole('combobox')).find((el) => el.tagName === 'BUTTON')!;

    await user.click(combobox);
    await user.type(await screen.findByPlaceholderText('Search categories'), 'Travel');
    await user.click(await screen.findByText('Create "Travel"'));

    // The combobox is replaced by the inline create panel -- confirm the category combobox
    // trigger is gone and the panel's own name input (prefilled with the typed text) has taken
    // its place.
    expect(combobox).not.toBeInTheDocument();
    const nameInput = screen.getByPlaceholderText('Category name');
    expect(nameInput).toHaveValue('Travel');

    await user.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => {
      const restoredCombobox = screen.getAllByRole('combobox').find((el) => el.tagName === 'BUTTON')!;
      expect(restoredCombobox).toHaveTextContent('Travel');
    });
    expect(categoriesApi.create).toHaveBeenCalledWith('Travel', 'tag', 'gray');
  });
});

describe('Ledger — merchant group review card', () => {
  beforeEach(() => {
    vi.mocked(transactionsApi.search).mockReset().mockResolvedValue({
      items: [], totalItems: 0, page: 0, size: 20, totalPages: 0,
    } as never);
    vi.mocked(transactionsApi.needsReview).mockReset().mockResolvedValue([]);
    vi.mocked(categoriesApi.list).mockReset().mockResolvedValue([]);
  });

  it('renders the merchant group review card above the transaction list', async () => {
    renderLedger();
    expect(await screen.findByTestId('merchant-group-review-card')).toBeInTheDocument();
  });
});
