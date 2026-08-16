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

  function daysFromNow(n: number): string {
    const d = new Date();
    d.setDate(d.getDate() + n);
    return d.toISOString().slice(0, 10);
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

// D-21, Step 1: "First Run Experience." A zero-transaction account (brand-new signup, or an
// existing account that connected Gmail/created an account but never got any data in) gets a
// welcome screen with 3 setup-path choices instead of the normal ₹0-everywhere dashboard body.
describe('Dashboard — empty-state welcome (D-21)', () => {
  const ACCOUNT = { id: 'acct-1', name: 'HDFC Savings', accountType: 'SAVINGS' as const, balance: 0 } as any;

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

  it('shows the welcome screen instead of the normal dashboard when there are zero transactions', async () => {
    renderDashboard();

    // The greeting reads fullName from AuthContext (real, unmocked here), not from userApi.get()
    // -- with no login/register call in this test it falls back to "there", which is fine; this
    // is only checking the welcome screen itself renders, not which name it shows. The greeting
    // interpolates {greeting}, {firstName} and "👋" as separate JSX text nodes, so this checks the
    // heading's full textContent rather than matching a single node's text.
    const heading = await screen.findByRole('heading', { level: 1 });
    expect(heading.textContent).toMatch(/there/);
    expect(heading.textContent).toMatch(/👋/);
    expect(screen.getByText('Import a statement')).toBeInTheDocument();
    expect(screen.getByText('Connect Gmail')).toBeInTheDocument();
    expect(screen.getByText('Add manually')).toBeInTheDocument();
    // The normal dashboard's own KPI grid must not also render underneath.
    expect(screen.queryByText('Financial Health Score')).not.toBeInTheDocument();
    expect(screen.queryByText('Total Balance')).not.toBeInTheDocument();
  });

  it('links Import a statement to the existing Import page', async () => {
    renderDashboard();
    await screen.findByText('Import a statement');

    expect(screen.getByRole('link', { name: /import a statement/i })).toHaveAttribute('href', '/app/import');
  });

  it('links Connect Gmail to the existing Settings page', async () => {
    renderDashboard();
    await screen.findByText('Connect Gmail');

    expect(screen.getByRole('link', { name: /connect gmail/i })).toHaveAttribute('href', '/app/settings');
  });

  it('opens the Add Transaction modal from Add manually', async () => {
    vi.mocked(accountsApi.list).mockResolvedValue([ACCOUNT]);
    const user = userEvent.setup();
    renderDashboard();
    await user.click(await screen.findByRole('button', { name: /add manually/i }));

    expect(await screen.findByRole('heading', { name: /add transaction/i })).toBeInTheDocument();
  });

  it('directs to Setup instead of a broken form when there are no accounts to attach a transaction to', async () => {
    vi.mocked(accountsApi.list).mockResolvedValue([]);
    const user = userEvent.setup();
    renderDashboard();
    await user.click(await screen.findByRole('button', { name: /add manually/i }));

    expect(await screen.findByText(/you'll need an account/i)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /add an account/i })).toHaveAttribute('href', '/app/setup');
    expect(screen.queryByLabelText(/description/i)).not.toBeInTheDocument();
  });

  it('creates a transaction with the form values and closes the modal on success', async () => {
    vi.mocked(accountsApi.list).mockResolvedValue([ACCOUNT]);
    vi.mocked(transactionsApi.create).mockResolvedValue({} as any);
    const user = userEvent.setup();
    renderDashboard();
    await user.click(await screen.findByRole('button', { name: /add manually/i }));
    await screen.findByRole('heading', { name: /add transaction/i });

    await user.type(screen.getByLabelText(/description/i), 'Coffee with a friend');
    await user.type(screen.getByLabelText(/amount/i), '250');
    await user.selectOptions(screen.getByLabelText(/category/i), 'Groceries');
    await user.click(screen.getByRole('button', { name: /^add transaction$/i }));

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
    await user.click(await screen.findByRole('button', { name: /add manually/i }));
    await screen.findByRole('heading', { name: /add transaction/i });

    expect(screen.getByRole('button', { name: /^add transaction$/i })).toBeDisabled();
  });

  it('shows the backend error inline and keeps the modal open on failure', async () => {
    vi.mocked(accountsApi.list).mockResolvedValue([ACCOUNT]);
    vi.mocked(transactionsApi.create).mockRejectedValue({
      response: { data: { message: 'That amount is not valid.' } },
    });
    const user = userEvent.setup();
    renderDashboard();
    await user.click(await screen.findByRole('button', { name: /add manually/i }));
    await screen.findByRole('heading', { name: /add transaction/i });

    await user.type(screen.getByLabelText(/description/i), 'Coffee');
    await user.type(screen.getByLabelText(/amount/i), '250');
    await user.click(screen.getByRole('button', { name: /^add transaction$/i }));

    expect(await screen.findByText('That amount is not valid.')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: /add transaction/i })).toBeInTheDocument();
  });

  it('closes without creating anything on Cancel', async () => {
    vi.mocked(accountsApi.list).mockResolvedValue([ACCOUNT]);
    const user = userEvent.setup();
    renderDashboard();
    await user.click(await screen.findByRole('button', { name: /add manually/i }));
    await screen.findByRole('heading', { name: /add transaction/i });

    await user.click(screen.getByRole('button', { name: /^cancel$/i }));

    expect(screen.queryByRole('heading', { name: /add transaction/i })).not.toBeInTheDocument();
    expect(transactionsApi.create).not.toHaveBeenCalled();
  });
});
