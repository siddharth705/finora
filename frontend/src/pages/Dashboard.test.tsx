import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, within, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Dashboard from './Dashboard';
import { AuthProvider } from '../context/AuthContext';
import {
  dashboardApi, accountsApi, transactionsApi, categoriesApi, goalsApi, insightsApi, userApi, budgetsApi, reportsApi, recurringApi,
} from '../api/endpoints';
import type { DashboardSummary } from '../types';

// Dashboard had no prior test file -- this covers only what each change added (the Financial
// Health Score card, D-19 Step 1; the Subscriptions & Recurring Payments card, C6.5; the D-21
// empty-state welcome screen), not the whole page. Every card renders data
// DashboardService/RecurringService already computed; nothing rendered any of it before these
// changes.
vi.mock('../api/endpoints', () => ({
  dashboardApi: { summary: vi.fn() },
  accountsApi: { list: vi.fn() },
  transactionsApi: { search: vi.fn(), create: vi.fn() },
  categoriesApi: { list: vi.fn() },
  goalsApi: { list: vi.fn() },
  insightsApi: { get: vi.fn() },
  userApi: { get: vi.fn() },
  budgetsApi: { list: vi.fn() },
  reportsApi: { availableMonths: vi.fn(), forMonth: vi.fn() },
  recurringApi: { list: vi.fn() },
}));

function summary(overrides: Partial<DashboardSummary> = {}): DashboardSummary {
  return {
    currentBalance: 50000,
    totalAssets: 60000,
    totalLiabilities: 10000,
    netWorth: 50000,
    monthlyIncome: 80000,
    monthlyExpense: 45000,
    netCashFlow: 35000,
    savingsRatePct: 43.75,
    incomeDeltaPct: null,
    expenseDeltaPct: null,
    netDeltaPct: null,
    healthScore: 82,
    healthLabel: 'Excellent',
    healthBreakdown: {
      'Savings Rate': 83,
      'Debt Utilization': 100,
      'Emergency Fund': 70,
      'Spend Consistency': 50,
      'Cash Flow Stability': 80,
    },
    healthScoreAvailable: true,
    healthScoreTransactionCount: 12,
    healthScoreMinTransactions: 10,
    spendByCategory: {},
    notifications: [],
    reportingMonth: '2026-08',
    reportingMonthIsCurrent: true,
    ...overrides,
  };
}

function renderDashboard() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <MemoryRouter>
          <Dashboard />
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>
  );
}

describe('Dashboard — Financial Health Score', () => {
  beforeEach(() => {
    vi.mocked(dashboardApi.summary).mockReset().mockResolvedValue(summary());
    vi.mocked(accountsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(transactionsApi.search).mockReset().mockResolvedValue({
      // totalElements > 0: this account has real history, matching every scenario these two
      // describe blocks actually test (a populated Health Score, real recurring items) -- 0 would
      // trip D-21's empty-state welcome screen instead of rendering the dashboard under test.
      // content stays [] since neither describe block asserts on the Recent Transactions list.
      content: [], page: 0, size: 4, totalElements: 12, totalPages: 3,
    });
    vi.mocked(goalsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(insightsApi.get).mockReset().mockResolvedValue({ sentences: [], movers: [] });
    vi.mocked(userApi.get).mockReset().mockResolvedValue({
      email: 'amy@example.test', fullName: 'Amy Santiago', lowBalanceThreshold: 2000,
      theme: 'system', timezone: 'Asia/Kolkata', phoneNumber: '+919876500000',
      phoneVerified: true, createdAt: '2026-01-01T00:00:00Z', passwordChangedAt: null,
    });
    vi.mocked(budgetsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(reportsApi.availableMonths).mockReset().mockResolvedValue(['2026-08']);
    vi.mocked(reportsApi.forMonth).mockReset().mockResolvedValue({
      month: '2026-08', income: 80000, expense: 45000, categories: [],
    });
    vi.mocked(recurringApi.list).mockReset().mockResolvedValue([]);
  });

  it('shows the score and label the backend already computed', async () => {
    renderDashboard();

    expect(await screen.findByText('Financial Health Score')).toBeInTheDocument();
    expect(screen.getByText('82')).toBeInTheDocument();
    expect(screen.getByText('Excellent')).toBeInTheDocument();
  });

  it('shows every component of the breakdown with its own percentage', async () => {
    renderDashboard();

    const heading = await screen.findByText('Financial Health Score');
    // "Savings Rate" is also a KPI tile label elsewhere on the page -- scope to the health card
    // itself (the heading's own section) rather than the whole document.
    const card = within(heading.closest('div.bg-card') as HTMLElement);
    expect(card.getByText('Savings Rate')).toBeInTheDocument();
    expect(card.getByText('Debt Utilization')).toBeInTheDocument();
    expect(card.getByText('Emergency Fund')).toBeInTheDocument();
    expect(card.getByText('Spend Consistency')).toBeInTheDocument();
    expect(card.getByText('Cash Flow Stability')).toBeInTheDocument();
    expect(card.getByText('100%')).toBeInTheDocument(); // Debt Utilization
    expect(card.getByText('50%')).toBeInTheDocument(); // Spend Consistency
  });

  it('reflects a low score honestly rather than always looking healthy', async () => {
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary({
      healthScore: 28, healthLabel: 'Needs Attention',
      healthBreakdown: { 'Savings Rate': 10, 'Debt Utilization': 20, 'Emergency Fund': 15, 'Spend Consistency': 40, 'Cash Flow Stability': 35 },
    }));
    renderDashboard();

    expect(await screen.findByText('28')).toBeInTheDocument();
    expect(screen.getByText('Needs Attention')).toBeInTheDocument();
  });

  it("D-25 PR3-A: shows a 'Getting Started' progress state instead of a score below the transaction floor", async () => {
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary({
      healthScore: null, healthLabel: null, healthBreakdown: {},
      healthScoreAvailable: false, healthScoreTransactionCount: 7, healthScoreMinTransactions: 10,
    }));
    renderDashboard();

    const heading = await screen.findByText('Financial Health Score');
    const card = within(heading.closest('div.bg-card') as HTMLElement);
    expect(card.getByText('Getting Started')).toBeInTheDocument();
    expect(card.getByText('7 / 10 transactions')).toBeInTheDocument();
    expect(card.getByText('70%')).toBeInTheDocument();
    // Not a real score or breakdown -- rendering either here would be the exact harsh-first-
    // impression bug this state exists to avoid. Scoped to the card itself: "Savings Rate" is
    // also a KPI tile label elsewhere on the page, same reason the breakdown test above scopes.
    expect(card.queryByText('out of 100')).not.toBeInTheDocument();
    expect(card.queryByText('Savings Rate')).not.toBeInTheDocument();
  });
});

describe('Dashboard — Subscriptions & Recurring Payments', () => {
  beforeEach(() => {
    vi.mocked(dashboardApi.summary).mockReset().mockResolvedValue(summary());
    vi.mocked(accountsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(transactionsApi.search).mockReset().mockResolvedValue({
      // totalElements > 0: this account has real history, matching every scenario these two
      // describe blocks actually test (a populated Health Score, real recurring items) -- 0 would
      // trip D-21's empty-state welcome screen instead of rendering the dashboard under test.
      // content stays [] since neither describe block asserts on the Recent Transactions list.
      content: [], page: 0, size: 4, totalElements: 12, totalPages: 3,
    });
    vi.mocked(goalsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(insightsApi.get).mockReset().mockResolvedValue({ sentences: [], movers: [] });
    vi.mocked(userApi.get).mockReset().mockResolvedValue({
      email: 'amy@example.test', fullName: 'Amy Santiago', lowBalanceThreshold: 2000,
      theme: 'system', timezone: 'Asia/Kolkata', phoneNumber: '+919876500000',
      phoneVerified: true, createdAt: '2026-01-01T00:00:00Z', passwordChangedAt: null,
    });
    vi.mocked(budgetsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(reportsApi.availableMonths).mockReset().mockResolvedValue(['2026-08']);
    vi.mocked(reportsApi.forMonth).mockReset().mockResolvedValue({
      month: '2026-08', income: 80000, expense: 45000, categories: [],
    });
  });

  // Bug fix: .toISOString() returns the UTC calendar date, but Dashboard.tsx's expectedLabel()
  // compares against LOCAL midnight -- the two disagree for roughly 5.5 hours overnight IST
  // (UTC has not yet rolled to the next day while local time already has), which made
  // daysFromNow(0) intermittently build "yesterday" in UTC while the app considered it "today"
  // locally. Local date components instead, matching expectedLabel()'s own local-date semantics.
  function daysFromNow(n: number): string {
    const d = new Date();
    d.setDate(d.getDate() + n);
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const day = String(d.getDate()).padStart(2, '0');
    return `${y}-${m}-${day}`;
  }

  it('renders each recurring item RecurringService already detected, with its own cadence and amount', async () => {
    vi.mocked(recurringApi.list).mockResolvedValue([
      { merchant: 'Netflix', label: 'Monthly', averageAmount: 649, occurrences: 4, lastDate: '2026-07-24', nextEstimate: daysFromNow(5) },
    ]);
    renderDashboard();

    expect(await screen.findByText('Subscriptions & Recurring Payments')).toBeInTheDocument();
    expect(screen.getByText('Netflix')).toBeInTheDocument();
    expect(screen.getByText('Monthly')).toBeInTheDocument();
    expect(screen.getByText('₹649')).toBeInTheDocument();
    expect(screen.getByText(/expected in 5 days/)).toBeInTheDocument();
  });

  it('shows no card at all when nothing is recurring, rather than an empty section', async () => {
    vi.mocked(recurringApi.list).mockResolvedValue([]);
    renderDashboard();

    await screen.findByText('Financial Health Score'); // page has finished loading
    expect(screen.queryByText('Subscriptions & Recurring Payments')).not.toBeInTheDocument();
  });

  it("says 'expected today' rather than a day count for a projection landing on the current date", async () => {
    vi.mocked(recurringApi.list).mockResolvedValue([
      { merchant: 'Spotify', label: 'Monthly', averageAmount: 119, occurrences: 3, lastDate: '2026-07-01', nextEstimate: daysFromNow(0) },
    ]);
    renderDashboard();

    expect(await screen.findByText('expected today')).toBeInTheDocument();
  });

  it('says "expected around" rather than a negative day count for a prediction already in the past', async () => {
    vi.mocked(recurringApi.list).mockResolvedValue([
      { merchant: 'Old Gym Membership', label: 'Monthly', averageAmount: 999, occurrences: 5, lastDate: '2026-05-01', nextEstimate: daysFromNow(-10) },
    ]);
    renderDashboard();

    expect(await screen.findByText(/expected around/)).toBeInTheDocument();
  });
});

// D-21: "First Run Experience." A zero-transaction account (brand-new signup, or an existing
// account that connected Gmail/created an account but never got any data in) sees the full
// dashboard shell with a friendly empty state PER SECTION, rather than the original single-gate
// welcome screen this redesign replaced (which hid the whole page behind one "pick a path" screen
// before showing anything else) or the earlier bare "₹0 everywhere" fallback before that.
describe('Dashboard — per-section empty states', () => {
  // A real bank shape, not `as any` -- BankLogo reads bank.id/officialName/websiteUrl directly,
  // and the Accounts Overview card renders it for whatever REAL accounts exist regardless of
  // whether transactions are empty (see the next describe block below), so an incomplete fixture
  // here would crash exactly the scenario this file needs to prove works.
  const BANK = {
    id: 'hdfc', officialName: 'HDFC Bank', shortName: 'HDFC', colorHex: '#004c8f', initials: 'HD',
    logoPath: '/banks/hdfc.svg', category: 'PRIVATE' as const, websiteUrl: 'https://hdfcbank.com',
    ifscPrefix: 'HDFC', supportedAccountTypes: ['SAVINGS'],
  };
  const ACCOUNT = { id: 'acct-1', name: 'HDFC Savings', accountType: 'SAVINGS' as const, balance: 0, bank: BANK } as any;

  beforeEach(() => {
    vi.mocked(dashboardApi.summary).mockReset().mockResolvedValue(summary());
    vi.mocked(accountsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(transactionsApi.search).mockReset().mockResolvedValue({
      content: [], page: 0, size: 4, totalElements: 0, totalPages: 0,
    });
    vi.mocked(transactionsApi.create).mockReset();
    vi.mocked(categoriesApi.list).mockReset().mockResolvedValue([
      { id: 'cat-1', name: 'Groceries' } as any,
      { id: 'cat-2', name: 'Salary' } as any,
    ]);
    vi.mocked(goalsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(insightsApi.get).mockReset().mockResolvedValue({ sentences: [], movers: [] });
    vi.mocked(userApi.get).mockReset().mockResolvedValue({
      email: 'amy@example.test', fullName: 'Amy Santiago', lowBalanceThreshold: 2000,
      theme: 'system', timezone: 'Asia/Kolkata', phoneNumber: '+919876500000',
      phoneVerified: true, createdAt: '2026-01-01T00:00:00Z', passwordChangedAt: null,
    });
    vi.mocked(budgetsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(reportsApi.availableMonths).mockReset().mockResolvedValue([]);
    vi.mocked(reportsApi.forMonth).mockReset();
    vi.mocked(recurringApi.list).mockReset().mockResolvedValue([]);
  });

  it('shows a friendly empty state per section, and hides Financial Health Score, when there are zero transactions', async () => {
    renderDashboard();

    // The shell itself is still here -- the greeting, the KPI row -- unlike the single-gate
    // welcome screen this replaced, which hid all of it behind one page.
    const heading = await screen.findByRole('heading', { level: 1 });
    expect(heading.textContent).toMatch(/there/);
    expect(heading.textContent).toMatch(/👋/);
    expect(screen.getByText('Total Balance')).toBeInTheDocument();

    expect(screen.getByText('No data yet')).toBeInTheDocument(); // Cash Flow
    expect(screen.getByText('No spending data yet')).toBeInTheDocument(); // Spending Breakdown
    expect(screen.getByText('No accounts yet')).toBeInTheDocument();
    expect(screen.getByText('No transactions yet')).toBeInTheDocument();
    expect(screen.getByText('No budgets set')).toBeInTheDocument();
    expect(screen.getByText('No goals yet')).toBeInTheDocument();
    // A score computed from zero transactions has nothing real behind it.
    expect(screen.queryByText('Financial Health Score')).not.toBeInTheDocument();
  });

  it('still shows AI Insights and Quick Actions -- generic tips, not hidden entirely', async () => {
    renderDashboard();
    await screen.findByText('No transactions yet');

    expect(screen.getByText('AI Insights')).toBeInTheDocument();
    expect(screen.getByText(/upload or import more transactions/i)).toBeInTheDocument();
    expect(screen.getByText('Quick Actions')).toBeInTheDocument();
  });

  it("shows a real account in Accounts Overview even when transactions are empty -- it's not gated on the same isEmpty flag", async () => {
    vi.mocked(accountsApi.list).mockResolvedValue([ACCOUNT]);
    renderDashboard();

    expect(await screen.findByText('HDFC Savings')).toBeInTheDocument();
    expect(screen.queryByText('No accounts yet')).not.toBeInTheDocument();
    // Recent Transactions is a separate section with its own, still-empty data.
    expect(screen.getByText('No transactions yet')).toBeInTheDocument();
  });

  it('links the Cash Flow empty state\'s Import Statement CTA to the existing Import page', async () => {
    renderDashboard();
    // Scoped to the Cash Flow card -- "Import Statement" is also Quick Actions' own link name,
    // both visible on screen at once.
    const cashFlowCard = within((await screen.findByText('No data yet')).closest('.bg-card') as HTMLElement);

    expect(cashFlowCard.getByRole('link', { name: /import statement/i })).toHaveAttribute('href', '/app/import');
  });

  it('opens the Add Transaction modal from Recent Transactions\' empty-state CTA', async () => {
    vi.mocked(accountsApi.list).mockResolvedValue([ACCOUNT]);
    const user = userEvent.setup();
    renderDashboard();
    await user.click(await screen.findByRole('button', { name: /\+ add transaction/i }));

    expect(await screen.findByRole('heading', { name: /add transaction/i })).toBeInTheDocument();
  });

  it('directs to Setup instead of a broken form when there are no accounts to attach a transaction to', async () => {
    const user = userEvent.setup();
    renderDashboard();
    await user.click(await screen.findByRole('button', { name: /\+ add transaction/i }));

    expect(await screen.findByText(/you'll need an account/i)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /add an account/i })).toHaveAttribute('href', '/app/setup');
    expect(screen.queryByLabelText(/description/i)).not.toBeInTheDocument();
  });

  it('creates a transaction with the form values and closes the modal on success', async () => {
    vi.mocked(accountsApi.list).mockResolvedValue([ACCOUNT]);
    vi.mocked(transactionsApi.create).mockResolvedValue({} as any);
    const user = userEvent.setup();
    renderDashboard();
    await user.click(await screen.findByRole('button', { name: /\+ add transaction/i }));
    // Scoped to the modal (not the whole screen) from here on -- "Add transaction" is also the
    // still-visible Quick Actions button's own name once the modal is open on top of it.
    const modal = within((await screen.findByRole('heading', { name: /add transaction/i })).closest('.bg-card') as HTMLElement);

    await user.type(modal.getByLabelText(/description/i), 'Coffee with a friend');
    await user.type(modal.getByLabelText(/amount/i), '250');
    await user.selectOptions(modal.getByLabelText(/category/i), 'Groceries');
    await user.click(modal.getByRole('button', { name: /^add transaction$/i }));

    await waitFor(() => expect(transactionsApi.create).toHaveBeenCalledWith(
      expect.objectContaining({
        accountId: 'acct-1', description: 'Coffee with a friend', amount: 250, type: 'EXPENSE', categoryName: 'Groceries',
        // Explicit [], not omitted -- Transaction.tags is typed non-nullable everywhere it's read.
        tags: [],
      })
    ));
    await waitFor(() => expect(screen.queryByRole('heading', { name: /add transaction/i })).not.toBeInTheDocument());
  });

  it('keeps the required fields disabled from submitting an empty form', async () => {
    vi.mocked(accountsApi.list).mockResolvedValue([ACCOUNT]);
    const user = userEvent.setup();
    renderDashboard();
    await user.click(await screen.findByRole('button', { name: /\+ add transaction/i }));
    const modal = within((await screen.findByRole('heading', { name: /add transaction/i })).closest('.bg-card') as HTMLElement);

    expect(modal.getByRole('button', { name: /^add transaction$/i })).toBeDisabled();
  });

  it('shows the backend error inline and keeps the modal open on failure', async () => {
    vi.mocked(accountsApi.list).mockResolvedValue([ACCOUNT]);
    vi.mocked(transactionsApi.create).mockRejectedValue({
      response: { data: { message: 'That amount is not valid.' } },
    });
    const user = userEvent.setup();
    renderDashboard();
    await user.click(await screen.findByRole('button', { name: /\+ add transaction/i }));
    const modal = within((await screen.findByRole('heading', { name: /add transaction/i })).closest('.bg-card') as HTMLElement);

    await user.type(modal.getByLabelText(/description/i), 'Coffee');
    await user.type(modal.getByLabelText(/amount/i), '250');
    await user.click(modal.getByRole('button', { name: /^add transaction$/i }));

    expect(await screen.findByText('That amount is not valid.')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: /add transaction/i })).toBeInTheDocument();
  });

  it('closes without creating anything on Cancel', async () => {
    vi.mocked(accountsApi.list).mockResolvedValue([ACCOUNT]);
    const user = userEvent.setup();
    renderDashboard();
    await user.click(await screen.findByRole('button', { name: /\+ add transaction/i }));
    await screen.findByRole('heading', { name: /add transaction/i });

    await user.click(screen.getByRole('button', { name: /^cancel$/i }));

    expect(screen.queryByRole('heading', { name: /add transaction/i })).not.toBeInTheDocument();
    expect(transactionsApi.create).not.toHaveBeenCalled();
  });
});
