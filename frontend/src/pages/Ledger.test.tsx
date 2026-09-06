import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Ledger from './Ledger';
import { transactionsApi, categoriesApi, accountsApi, budgetsApi } from '../api/endpoints';
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
  // Redesign added the KPI row's account column and "This Month" budget card -- both fetch
  // through these two, on top of the transactions/categories calls this file already mocked.
  accountsApi: { list: vi.fn() },
  budgetsApi: { list: vi.fn() },
}));

// Safe defaults for every test in this file -- most tests care about transactions/categories
// behavior and never override these two, so they'd otherwise resolve to `undefined` and throw
// inside the KPI row's `(accounts ?? []).map(...)` / `(budgets ?? []).map(...)`.
beforeEach(() => {
  vi.mocked(accountsApi.list).mockReset().mockResolvedValue([]);
  vi.mocked(budgetsApi.list).mockReset().mockResolvedValue([]);
});

// Real MerchantGroupReviewCard calls transactionsApi.groupsNeedsReview, which the mock above
// doesn't define -- this file's tests are about the "Why this category?" panel, not the merchant-
// group card, so it's stubbed to a static marker rather than pulled into the shared endpoints mock.
vi.mock('../components/MerchantGroupReviewCard', () => ({
  MerchantGroupReviewCard: () => <div data-testid="merchant-group-review-card" />,
}));

// Same reasoning as MerchantGroupReviewCard just above: the real component calls
// transactionsApi.groupsNeedsReviewByCounterparty, undefined on the mock above.
vi.mock('../components/CounterpartyGroupReviewCard', () => ({
  CounterpartyGroupReviewCard: () => <div data-testid="counterparty-group-review-card" />,
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
    // UNKNOWN by default so the counterparty badge renders nothing unless a test asks for it --
    // every existing assertion in this file predates the badge and should stay unaffected by it.
    counterpartyType: 'UNKNOWN',
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

/**
 * `t.reconciliationStatus` used to render straight into the Status column unfiltered -- `OK`,
 * the status of nearly every ordinary transaction, looked exactly as prominent as `DUPLICATE`
 * and meant nothing to a person reading their ledger. Reported directly: "what is this OK
 * status?". These lock in both halves of the fix: OK disappears, and every other status becomes
 * a clickable, human-labelled badge that opens the SAME explanation panel the "Why this
 * category?" icon already uses -- transactionsApi.explanation was already computing the full
 * reconciliation reasoning server-side; it just had nowhere to go.
 */
describe('Ledger — Status column', () => {
  beforeEach(() => {
    vi.mocked(transactionsApi.needsReview).mockReset().mockResolvedValue([]);
    vi.mocked(categoriesApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(transactionsApi.explanation).mockReset();
  });

  it('shows no badge at all for an ordinary (OK) transaction', async () => {
    vi.mocked(transactionsApi.search).mockReset().mockResolvedValue({
      content: [txn({ reconciliationStatus: 'OK' })], page: 0, size: 10, totalElements: 1, totalPages: 1,
    });
    renderLedger();

    const description = await screen.findByText('AMAZON PAY');
    const row = description.closest('tr')!;

    // Exhaustive within the ROW, not a name-pattern guess: an OK row's only buttons are "Why
    // this category?" (the category cell's icon) and the two row actions -- anything beyond
    // that set IS a Status badge, whatever it happens to be labelled. A regex over the six known
    // non-OK labels would pass even if OK itself grew a badge, since "OK"/"Ordinary" wouldn't
    // match that pattern. Scoped to the row (not the whole page) since the redesign added
    // page-level buttons (category chips, numbered pagination) this assertion isn't about.
    const buttonNames = within(row).getAllByRole('button').map((b) => b.getAttribute('title') ?? b.getAttribute('aria-label'));
    expect(buttonNames).toEqual(['Why this category?', 'Edit transaction', 'Delete transaction']);
  });

  it('shows a human label, not the raw enum, for a flagged transaction', async () => {
    vi.mocked(transactionsApi.search).mockReset().mockResolvedValue({
      content: [txn({ reconciliationStatus: 'DUPLICATE' })], page: 0, size: 10, totalElements: 1, totalPages: 1,
    });
    renderLedger();

    expect(await screen.findByRole('button', { name: 'Duplicate' })).toBeInTheDocument();
    expect(screen.queryByText('DUPLICATE')).not.toBeInTheDocument();
  });

  it('opens the explanation panel and shows the reconciliation reasoning when the badge is clicked', async () => {
    const user = userEvent.setup();
    vi.mocked(transactionsApi.search).mockReset().mockResolvedValue({
      content: [txn({ reconciliationStatus: 'DUPLICATE' })], page: 0, size: 10, totalElements: 1, totalPages: 1,
    });
    vi.mocked(transactionsApi.explanation).mockResolvedValue({
      decisionSource: 'MERCHANT_DEFAULT',
      summary: 'No rule, learned pattern, or keyword matched, so this defaulted to "Shopping".',
      evidence: [],
      reconciliation: {
        status: 'DUPLICATE',
        matchedTransactionId: 'txn-0',
        summary: 'Matched as a duplicate of an existing transaction — same account, date, amount, and description.',
        evidence: ['Same account', 'Same date', 'Same amount'],
      },
    });
    renderLedger();

    await user.click(await screen.findByRole('button', { name: 'Duplicate' }));

    expect(await screen.findByText(/matched as a duplicate of an existing transaction/i)).toBeInTheDocument();
    expect(screen.getByText('Same account')).toBeInTheDocument();
    // The category section (the icon's own original purpose) still renders underneath it.
    expect(screen.getByText(/defaulted to "shopping"/i)).toBeInTheDocument();
    expect(transactionsApi.explanation).toHaveBeenCalledWith('txn-1');
  });

  it('does not render a reconciliation section for an ordinary transaction opened via the category icon', async () => {
    const user = userEvent.setup();
    vi.mocked(transactionsApi.search).mockReset().mockResolvedValue({
      content: [txn({ reconciliationStatus: 'OK' })], page: 0, size: 10, totalElements: 1, totalPages: 1,
    });
    vi.mocked(transactionsApi.explanation).mockResolvedValue({
      decisionSource: 'MANUAL', summary: 'You set this category yourself.', evidence: [],
      // No `reconciliation` field, matching what the real endpoint returns for status OK
      // (TransactionExplanationService.reconciliationExplanationFor returns null for it).
    });
    renderLedger();

    await user.click(await screen.findByTitle('Why this category?'));

    await screen.findByText('You set this category yourself.');
    expect(screen.queryByText(/matched as a/i)).not.toBeInTheDocument();
  });
});

/**
 * Reported directly: "in this search bar add to search category as well" -- the placeholder is
 * the one visible promise about what the search box does, and it was missing category even
 * though every other matched field (description, merchant, bank, account, branch, IFSC) was
 * listed. The actual matching happens server-side (TransactionRepositoryIT); this only locks in
 * that the UI's own promise mentions it.
 */
describe('Ledger — search bar', () => {
  it('tells the user category is one of the things it searches', async () => {
    vi.mocked(transactionsApi.search).mockReset().mockResolvedValue({
      content: [], page: 0, size: 10, totalElements: 0, totalPages: 0,
    });
    vi.mocked(transactionsApi.needsReview).mockReset().mockResolvedValue([]);
    vi.mocked(categoriesApi.list).mockReset().mockResolvedValue([]);
    renderLedger();

    expect(await screen.findByPlaceholderText(/category/i)).toBeInTheDocument();
  });
});

/**
 * Reported directly: "I need filter here" -- there was no way to find (or exclude) transactions
 * by status at all, even though the Status column now names exactly this vocabulary on every row.
 */
describe('Ledger — Status filter', () => {
  it('sends the chosen status to the search endpoint and resets to page 0', async () => {
    const user = userEvent.setup();
    vi.mocked(transactionsApi.search).mockReset().mockResolvedValue({
      content: [], page: 0, size: 10, totalElements: 0, totalPages: 0,
    });
    vi.mocked(transactionsApi.needsReview).mockReset().mockResolvedValue([]);
    vi.mocked(categoriesApi.list).mockReset().mockResolvedValue([]);
    renderLedger();

    await waitFor(() => expect(transactionsApi.search).toHaveBeenCalled());
    vi.mocked(transactionsApi.search).mockClear();

    await user.selectOptions(screen.getByDisplayValue('All Statuses'), 'DUPLICATE');

    await waitFor(() =>
      expect(transactionsApi.search).toHaveBeenCalledWith(expect.objectContaining({ status: 'DUPLICATE', page: 0 }))
    );
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

  it('renders the counterparty group review card too, after the merchant one', async () => {
    renderLedger();
    expect(await screen.findByTestId('counterparty-group-review-card')).toBeInTheDocument();
  });
});

// Animation-polish roadmap Phase 2 (§3 priority 2): the table body's plain "Loading…" text row
// became a skeleton, and the row-action/pagination buttons became IconButton.
describe('Ledger — Phase 2 table skeleton and IconButton migration', () => {
  beforeEach(() => {
    vi.mocked(transactionsApi.needsReview).mockReset().mockResolvedValue([]);
    vi.mocked(categoriesApi.list).mockReset().mockResolvedValue([]);
  });

  it('marks the table body as a busy live region while the initial search is loading', () => {
    // Never resolves during this test -- isLoading stays true for its whole duration.
    vi.mocked(transactionsApi.search).mockReset().mockReturnValue(new Promise(() => {}));
    const { container } = renderLedger();

    const tbody = container.querySelector('tbody')!;
    expect(tbody).toHaveAttribute('role', 'status');
    expect(tbody).toHaveAttribute('aria-busy', 'true');
    expect(tbody).toHaveAttribute('aria-live', 'polite');
  });

  // Regression test: the sr-only announcement row was originally nested inside the same
  // useDelayedLoading-gated block as the visible skeleton rows, so the live region had zero
  // children -- nothing to announce -- for the whole ~200ms delay window. It must render
  // immediately, independent of that delay, the same way ChartContainer's Skeleton.Region label
  // already does (that window exists to prevent a sighted-user flicker, which doesn't apply to a
  // screen-reader announcement).
  it('announces "Loading transactions" immediately, without waiting for the skeleton rows to appear', () => {
    vi.mocked(transactionsApi.search).mockReset().mockReturnValue(new Promise(() => {}));
    renderLedger();

    expect(screen.getByText('Loading transactions')).toBeInTheDocument();
  });

  it('renders real rows once the search resolves, with no residual loading row left behind', async () => {
    vi.mocked(transactionsApi.search).mockReset().mockResolvedValue({
      content: [txn({ id: 'txn-1', description: 'AMAZON PAY' })],
      page: 0, size: 10, totalElements: 1, totalPages: 1,
    });
    const { container } = renderLedger();

    expect(await screen.findByText('AMAZON PAY')).toBeInTheDocument();
    const tbody = container.querySelector('tbody')!;
    expect(tbody).not.toHaveAttribute('role', 'status');
    expect(tbody.querySelectorAll('.animate-pulse').length).toBe(0);
  });

  it('labels a person-to-person row by direction, not by the stored type alone', async () => {
    vi.mocked(transactionsApi.search).mockReset().mockResolvedValue({
      content: [
        txn({ id: 'txn-out', description: 'PAID SUNIL', type: 'EXPENSE', counterpartyType: 'PERSON' }),
        txn({ id: 'txn-in', description: 'GOT FROM SUNIL', type: 'INCOME', counterpartyType: 'PERSON' }),
      ],
      page: 0, size: 10, totalElements: 2, totalPages: 1,
    });
    renderLedger();

    await screen.findByText('PAID SUNIL');
    // Same stored counterparty type on both rows; the readable meaning differs because direction is
    // composed in at render time. This is the assertion that stops anyone "simplifying" the label
    // back into a stored string, which is the V123 mistake.
    expect(screen.getByTitle('Sent to a person')).toBeInTheDocument();
    expect(screen.getByTitle('Received from a person')).toBeInTheDocument();
    expect(screen.getAllByText('Person')).toHaveLength(2);
  });

  it('renders no counterparty badge at all when the counterparty is unknown', async () => {
    // Roughly a fifth of real rows, plus everything the server backfill has not reached. A badge
    // reading "unknown" on that many rows would be noise, so there must be no badge.
    vi.mocked(transactionsApi.search).mockReset().mockResolvedValue({
      content: [txn({ id: 'txn-1', description: 'AMAZON PAY', counterpartyType: 'UNKNOWN' })],
      page: 0, size: 10, totalElements: 1, totalPages: 1,
    });
    renderLedger();

    await screen.findByText('AMAZON PAY');
    expect(screen.queryByText(/unknown/i)).not.toBeInTheDocument();
    expect(screen.queryByText('Person')).not.toBeInTheDocument();
    expect(screen.queryByText('Business')).not.toBeInTheDocument();
  });

  it('exposes the edit and delete row actions by their accessible names', async () => {
    vi.mocked(transactionsApi.search).mockReset().mockResolvedValue({
      content: [txn({ id: 'txn-1', description: 'AMAZON PAY' })],
      page: 0, size: 10, totalElements: 1, totalPages: 1,
    });
    renderLedger();

    expect(await screen.findByRole('button', { name: 'Edit transaction' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Delete transaction' })).toBeInTheDocument();
  });

  it('shows the delete button in a loading state while the delete request is in flight', async () => {
    const user = userEvent.setup();
    vi.mocked(transactionsApi.search).mockReset().mockResolvedValue({
      content: [txn({ id: 'txn-1', description: 'AMAZON PAY' })],
      page: 0, size: 10, totalElements: 1, totalPages: 1,
    });
    let resolveRemove!: () => void;
    vi.mocked(transactionsApi.remove).mockReset().mockReturnValue(
      new Promise((resolve) => {
        resolveRemove = () => resolve(undefined as never);
      })
    );
    renderLedger();

    await user.click(await screen.findByRole('button', { name: 'Delete transaction' }));
    await user.click(await screen.findByRole('button', { name: 'Delete' })); // ConfirmDialog

    // IconButton suffixes its aria-label while loading -- that suffix IS the announcement of the
    // pending state, since aria-label replaces content so an sr-only span would never be read.
    const deleteButton = await screen.findByRole('button', { name: 'Delete transaction, loading' });
    expect(deleteButton).toBeDisabled();

    resolveRemove();
    await waitFor(() => expect(deleteButton).not.toBeDisabled());
    // ...and the suffix is gone once the request settles.
    expect(screen.getByRole('button', { name: 'Delete transaction' })).toBe(deleteButton);
  });

  it('exposes Previous/Next pagination controls by their accessible names', async () => {
    vi.mocked(transactionsApi.search).mockReset().mockResolvedValue({
      content: [txn()], page: 0, size: 1, totalElements: 2, totalPages: 2,
    });
    renderLedger();

    expect(await screen.findByRole('button', { name: 'Previous page' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Next page' })).toBeInTheDocument();
  });
});

// Redesign: KPI row (Total Spent / Transactions / Top Category / This Month) computed from a
// bounded stats fetch, and category chips (with real counts) that filter the ledger by category.
describe('Ledger — KPI row and category chips', () => {
  beforeEach(() => {
    vi.mocked(transactionsApi.needsReview).mockReset().mockResolvedValue([]);
  });

  it('shows total spend across the filtered window and lets a category chip filter the ledger', async () => {
    vi.mocked(transactionsApi.search).mockReset().mockResolvedValue({
      content: [
        txn({ id: 't1', categoryId: 'cat-1', categoryName: 'Shopping', amount: 1000, type: 'EXPENSE' }),
        txn({ id: 't2', categoryId: 'cat-1', categoryName: 'Shopping', amount: 500, type: 'EXPENSE' }),
        txn({ id: 't3', categoryId: 'cat-2', categoryName: 'Travel', amount: 200, type: 'EXPENSE' }),
      ],
      page: 0, size: 10, totalElements: 3, totalPages: 1,
    });
    vi.mocked(categoriesApi.list).mockReset().mockResolvedValue([
      { id: 'cat-1', name: 'Shopping', isSystem: false, icon: 'shopping-bag', color: 'blue' },
      { id: 'cat-2', name: 'Travel', isSystem: false, icon: 'plane', color: 'green' },
    ]);
    const user = userEvent.setup();
    renderLedger();

    // Total Spent KPI: 1000 + 500 + 200.
    expect(await screen.findByText('₹1,700')).toBeInTheDocument();

    await waitFor(() => expect(transactionsApi.search).toHaveBeenCalled());
    vi.mocked(transactionsApi.search).mockClear();

    await user.click(await screen.findByRole('button', { name: /travel\s*1/i }));

    await waitFor(() =>
      expect(transactionsApi.search).toHaveBeenCalledWith(expect.objectContaining({ categoryId: 'cat-2', page: 0 }))
    );
  });

  // Bug fix: the KPI stats query (statsFilters) never includes categoryId -- a chip's own count
  // must answer "how many rows in each category," which only works if selecting one doesn't
  // change what the others are counted against. The label must stay honest about that: it must
  // NOT claim "(filtered)" when the ONLY active filter is a category chip, since the number next
  // to it doesn't actually change. (It previously did claim this -- the value and label disagreed.)
  it('does not relabel the Total Spent KPI "(filtered)" when only a category chip is selected', async () => {
    vi.mocked(transactionsApi.search).mockReset().mockResolvedValue({
      content: [
        txn({ id: 't1', categoryId: 'cat-1', categoryName: 'Shopping', amount: 1000, type: 'EXPENSE' }),
        txn({ id: 't2', categoryId: 'cat-2', categoryName: 'Travel', amount: 200, type: 'EXPENSE' }),
      ],
      page: 0, size: 10, totalElements: 2, totalPages: 1,
    });
    vi.mocked(categoriesApi.list).mockReset().mockResolvedValue([
      { id: 'cat-1', name: 'Shopping', isSystem: false, icon: 'shopping-bag', color: 'blue' },
      { id: 'cat-2', name: 'Travel', isSystem: false, icon: 'plane', color: 'green' },
    ]);
    const user = userEvent.setup();
    renderLedger();

    expect(await screen.findByText('Total Spent')).toBeInTheDocument();
    expect(screen.queryByText('Total Spent (filtered)')).not.toBeInTheDocument();

    await user.click(await screen.findByRole('button', { name: /travel\s*1/i }));

    // The label must still read plain "Total Spent" -- and the value must still be the
    // all-categories total (₹1,200), matching what the (unchanged) label promises -- since
    // selecting a category chip alone doesn't change either.
    await waitFor(() => expect(screen.getByText('Total Spent')).toBeInTheDocument());
    expect(screen.queryByText('Total Spent (filtered)')).not.toBeInTheDocument();
    expect(screen.getByText('₹1,200')).toBeInTheDocument();
  });

  // Bug fix: the KPI row's loading skeleton only checked the transactions-stats query, not the
  // separate budgets query "This Month" reads -- so that card could render a real-looking
  // "₹0 / 0% of budget" before /budgets had actually responded.
  it('keeps showing the KPI skeleton until both the transactions stats AND budgets have loaded', async () => {
    vi.mocked(transactionsApi.search).mockReset().mockResolvedValue({
      content: [txn({ amount: 500, type: 'EXPENSE' })], page: 0, size: 10, totalElements: 1, totalPages: 1,
    });
    vi.mocked(categoriesApi.list).mockReset().mockResolvedValue([]);
    // Never resolves -- budgets stays "loading" for the whole test.
    vi.mocked(budgetsApi.list).mockReset().mockReturnValue(new Promise(() => {}));
    renderLedger();

    // The transactions side has already resolved (real content is visible below)...
    await screen.findByText('AMAZON PAY');
    // ...but the KPI row must still be the skeleton, not a "This Month" card claiming ₹0/0%.
    expect(screen.queryByText('This Month')).not.toBeInTheDocument();
    expect(screen.queryByText('0% of budget')).not.toBeInTheDocument();
    expect(screen.getByText('Loading transaction summary')).toBeInTheDocument();
  });

  // Bug fix: selecting a category, then narrowing another filter until that category has zero
  // matches, used to make its chip vanish entirely -- leaving neither "All" nor any chip
  // highlighted even though `categoryId` was still silently applied to the table query.
  it('keeps the selected category chip visible at zero count once another filter empties its matches', async () => {
    vi.mocked(transactionsApi.search).mockReset().mockImplementation((filters: any) => {
      const content = filters.dateFrom === '2026-01-01'
        ? [txn({ id: 't1', categoryId: 'cat-1', categoryName: 'Shopping', amount: 500, type: 'EXPENSE' })]
        : [
            txn({ id: 't1', categoryId: 'cat-1', categoryName: 'Shopping', amount: 500, type: 'EXPENSE' }),
            txn({ id: 't2', categoryId: 'cat-2', categoryName: 'Travel', amount: 200, type: 'EXPENSE' }),
          ];
      return Promise.resolve({ content, page: 0, size: 10, totalElements: content.length, totalPages: 1 });
    });
    vi.mocked(categoriesApi.list).mockReset().mockResolvedValue([
      { id: 'cat-1', name: 'Shopping', isSystem: false, icon: 'shopping-bag', color: 'blue' },
      { id: 'cat-2', name: 'Travel', isSystem: false, icon: 'plane', color: 'green' },
    ]);
    const user = userEvent.setup();
    renderLedger();

    await user.click(await screen.findByRole('button', { name: /travel\s*1/i }));
    await waitFor(() => expect(screen.getByRole('button', { name: /travel\s*1/i })).toHaveAttribute('class', expect.stringContaining('bg-primary')));

    // Narrow the date range to a window with no Travel transactions at all.
    fireEvent.change(screen.getByLabelText('From date'), { target: { value: '2026-01-01' } });

    // The Travel chip must still exist (now at zero count) and still read as selected -- not
    // vanish, and not silently fall back to "All" looking selected instead.
    const travelChip = await screen.findByRole('button', { name: /travel\s*0/i });
    expect(travelChip).toHaveAttribute('class', expect.stringContaining('bg-primary'));
    expect(screen.getByRole('button', { name: /^all/i })).not.toHaveAttribute('class', expect.stringContaining('bg-primary'));
  });
});

// Redesign: the new Account column resolves each row's accountId against accountsApi.list(),
// rendering the bank's own branding and its accountNumberMasked outright -- that field is
// already CsvParser.maskAccountNumber's output ("••••" + only the last 4 real digits), so
// there's no further-unmasked value left for a reveal-on-click control to ever uncover. A prior
// version routed this through MaskedAccountNumber (Setup.tsx/Import.tsx's reveal toggle), which
// just hid this already-safe string behind a second, pointless placeholder -- reported directly.
describe('Ledger — account column', () => {
  it("shows the row's bank name and masked account number once account data loads", async () => {
    vi.mocked(transactionsApi.search).mockReset().mockResolvedValue({
      content: [txn({ accountId: 'acc-1' })], page: 0, size: 10, totalElements: 1, totalPages: 1,
    });
    vi.mocked(transactionsApi.needsReview).mockReset().mockResolvedValue([]);
    vi.mocked(categoriesApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(accountsApi.list).mockReset().mockResolvedValue([
      {
        id: 'acc-1', name: 'My HDFC', accountType: 'SAVINGS', balance: 1000,
        accountNumberMasked: '••••4582',
        bank: {
          id: 'hdfc', officialName: 'HDFC Bank', shortName: 'HDFC Bank', colorHex: '#004C8F',
          initials: 'HD', logoPath: '/assets/banks/hdfc.svg', category: 'PRIVATE',
          websiteUrl: null, ifscPrefix: 'HDFC', supportedAccountTypes: ['SAVINGS'],
        },
        lastImportedAt: null, lastStatementPeriodStart: null, lastStatementPeriodEnd: null,
        statementsCount: 0, transactionsCount: 1, status: 'ACTIVE',
      },
    ]);
    renderLedger();

    expect(await screen.findByText('HDFC Bank')).toBeInTheDocument();
    // Shown directly, no click needed -- see the describe block's own comment for why.
    expect(screen.getByText('••••4582')).toBeInTheDocument();
  });
});

// Bug fix: needsCategoryReview and recurring are independent facts about a transaction --
// previously only the highest-priority one rendered as a Status badge, so a recurring charge
// that also needed a category review silently lost its "Recurring" tag the moment it needed
// review. Both must show at once.
describe('Ledger — Status column shows every applicable badge, not just the highest priority one', () => {
  beforeEach(() => {
    vi.mocked(transactionsApi.needsReview).mockReset().mockResolvedValue([]);
    vi.mocked(categoriesApi.list).mockReset().mockResolvedValue([]);
  });

  it('shows both Needs Review and Recurring for a transaction that is both', async () => {
    vi.mocked(transactionsApi.search).mockReset().mockResolvedValue({
      content: [txn({ needsCategoryReview: true, recurring: true })],
      page: 0, size: 10, totalElements: 1, totalPages: 1,
    });
    renderLedger();

    expect(await screen.findByText('Needs Review')).toBeInTheDocument();
    expect(screen.getByText('Recurring')).toBeInTheDocument();
  });

  it('falls back to Categorized/Reviewed only when neither needs-review nor recurring applies', async () => {
    vi.mocked(transactionsApi.search).mockReset().mockResolvedValue({
      content: [txn({ needsCategoryReview: false, recurring: false, categoryManuallySet: true })],
      page: 0, size: 10, totalElements: 1, totalPages: 1,
    });
    renderLedger();

    expect(await screen.findByText('Reviewed')).toBeInTheDocument();
    expect(screen.queryByText('Needs Review')).not.toBeInTheDocument();
    expect(screen.queryByText('Recurring')).not.toBeInTheDocument();
  });
});
