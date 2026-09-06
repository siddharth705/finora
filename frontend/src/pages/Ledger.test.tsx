import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Ledger from './Ledger';
import { transactionsApi, categoriesApi, onboardingApi } from '../api/endpoints';
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
  // Getting-started checklist dwell timer (D-onboarding) -- default to "no REVIEW_TRANSACTIONS
  // item in the response" so it never fires in tests that don't care about it; the dwell-timer's
  // own test overrides this.
  onboardingApi: {
    getChecklist: vi.fn().mockResolvedValue({ items: [], completedCount: 0, totalCount: 6 }),
    completeChecklistItem: vi.fn().mockResolvedValue(undefined),
  },
}));

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

    await screen.findByText('AMAZON PAY');

    // Exhaustive, not a name-pattern guess: an OK row's only buttons are "Why this category?"
    // (the category cell's icon), the two row actions, and pagination -- anything beyond that
    // set IS a Status badge, whatever it happens to be labelled. A regex over the six known
    // non-OK labels would pass even if OK itself grew a badge, since "OK"/"Ordinary" wouldn't
    // match that pattern.
    const buttonNames = screen.getAllByRole('button').map((b) => b.getAttribute('title') ?? b.getAttribute('aria-label'));
    expect(buttonNames).toEqual(['Why this category?', 'Edit transaction', 'Delete transaction', 'Previous page', 'Next page']);
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

describe('Ledger — getting-started checklist dwell timer', () => {
  beforeEach(() => {
    vi.mocked(transactionsApi.search).mockReset().mockResolvedValue({
      content: [txn()], page: 0, size: 10, totalElements: 1, totalPages: 1,
    });
    vi.mocked(transactionsApi.needsReview).mockReset().mockResolvedValue([]);
    vi.mocked(categoriesApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(onboardingApi.getChecklist).mockReset();
    vi.mocked(onboardingApi.completeChecklistItem).mockReset();
  });

  it('marks REVIEW_TRANSACTIONS complete after a 1.5s dwell', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.mocked(onboardingApi.getChecklist).mockResolvedValue({
      items: [{ key: 'REVIEW_TRANSACTIONS', completed: false }], completedCount: 0, totalCount: 6,
    });
    const completeSpy = vi.mocked(onboardingApi.completeChecklistItem).mockResolvedValue(undefined as any);

    renderLedger();

    await vi.waitFor(() => expect(onboardingApi.getChecklist).toHaveBeenCalled());
    await vi.advanceTimersByTimeAsync(1500);

    expect(completeSpy).toHaveBeenCalledWith('REVIEW_TRANSACTIONS');
    vi.useRealTimers();
  });

  it('does not fire if the item is already complete', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.mocked(onboardingApi.getChecklist).mockResolvedValue({
      items: [{ key: 'REVIEW_TRANSACTIONS', completed: true }], completedCount: 1, totalCount: 6,
    });
    const completeSpy = vi.mocked(onboardingApi.completeChecklistItem).mockResolvedValue(undefined as any);

    renderLedger();

    await vi.waitFor(() => expect(onboardingApi.getChecklist).toHaveBeenCalled());
    await vi.advanceTimersByTimeAsync(1500);

    expect(completeSpy).not.toHaveBeenCalled();
    vi.useRealTimers();
  });
});
