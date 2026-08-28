import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
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
  dashboardApi: { summary: vi.fn(), journey: vi.fn() },
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
      'Debt Score': 100,
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
    // Defaults to a mature account (not limited) so every existing test below, none of which
    // cares about this banner, keeps rendering exactly as it did before this field existed.
    limitedHistory: false,
    historyMonthCount: 6,
    limitedHistoryMonthFloor: 3,
    statementCount: 8,
    accountCount: 2,
    categoryReviewWarning: false,
    categoryReviewSpendPct: 0,
    categoryReviewSpendAmount: 0,
    categoryReviewTransactionCount: 0,
    categoryReviewSpendWarningThresholdPct: 20,
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
    // Empty by default -- FinancialJourney (a separate component with its own dedicated test
    // file) renders nothing for an empty milestone list, so this stays out of the way of every
    // assertion below unless a test explicitly cares about it.
    vi.mocked(dashboardApi.journey).mockReset().mockResolvedValue({ milestones: [] });
    vi.mocked(accountsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(categoriesApi.list).mockReset().mockResolvedValue([]);
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
      phoneVerified: true, createdAt: '2026-01-01T00:00:00Z', passwordChangedAt: null, signInMethod: 'PASSWORD',
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
    expect(card.getByText('Debt Score')).toBeInTheDocument();
    expect(card.getByText('Emergency Fund')).toBeInTheDocument();
    expect(card.getByText('Spend Consistency')).toBeInTheDocument();
    expect(card.getByText('Cash Flow Stability')).toBeInTheDocument();
    expect(card.getByText('100%')).toBeInTheDocument(); // Debt Score
    expect(card.getByText('50%')).toBeInTheDocument(); // Spend Consistency
  });

  it("colors each breakdown bar by its OWN score, not the overall label", async () => {
    // A perfect Debt Score (100 -- no credit card debt) must render as a healthy-colored bar even
    // when the overall health score is poor and every other component is struggling. Before this
    // fix, every bar inherited the overall label's color, so a 100 rendered as full-width red --
    // reading as "maxed out" regardless of what its own number said.
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary({
      healthScore: 28, healthLabel: 'Needs Attention',
      healthBreakdown: { 'Savings Rate': 0, 'Debt Score': 100, 'Emergency Fund': 9, 'Spend Consistency': 8, 'Cash Flow Stability': 50 },
    }));
    renderDashboard();

    const heading = await screen.findByText('Financial Health Score');
    const card = within(heading.closest('div.bg-card') as HTMLElement);
    const debtRow = card.getByText('Debt Score').closest('div')!.parentElement!;
    const debtBar = debtRow.querySelector('.bg-success, .bg-primary, .bg-warning, .bg-danger');
    expect(debtBar).toHaveClass('bg-success');

    const savingsRow = card.getByText('Savings Rate').closest('div')!.parentElement!;
    const savingsBar = savingsRow.querySelector('.bg-success, .bg-primary, .bg-warning, .bg-danger');
    expect(savingsBar).toHaveClass('bg-danger');
  });

  it('reflects a low score honestly rather than always looking healthy', async () => {
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary({
      healthScore: 28, healthLabel: 'Needs Attention',
      healthBreakdown: { 'Savings Rate': 10, 'Debt Score': 20, 'Emergency Fund': 15, 'Spend Consistency': 40, 'Cash Flow Stability': 35 },
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

describe('Dashboard — Spending Breakdown category review warning', () => {
  beforeEach(() => {
    vi.mocked(dashboardApi.summary).mockReset().mockResolvedValue(summary());
    vi.mocked(dashboardApi.journey).mockReset().mockResolvedValue({ milestones: [] });
    vi.mocked(accountsApi.list).mockReset().mockResolvedValue([]);
    // totalElements: 0 (isEmpty) -- these tests don't care about Financial Health Score, and
    // rendering it alongside a populated Doughnut chart mounts two Chart.js instances at once,
    // which crashes in jsdom ("can't acquire context from the given item", no error boundary to
    // catch it). The existing "0%, not NaN%" test below proves a populated Doughnut renders fine
    // on its own with isEmpty -- matching that pattern rather than the Health Score block's.
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
      phoneVerified: true, createdAt: '2026-01-01T00:00:00Z', passwordChangedAt: null, signInMethod: 'PASSWORD',
    });
    vi.mocked(budgetsApi.list).mockReset().mockResolvedValue([]);
    // Empty (not a real month) -- CashFlowChart isn't gated by the page-level isEmpty at all;
    // it's driven independently by these two APIs. Real data here mounts a SECOND live Chart.js
    // canvas (a Line chart) alongside the Doughnut these tests actually care about, and jsdom
    // can't survive two real chart.js instances mounting at once ("Failed to create chart:
    // can't acquire context from the given item", uncaught, unmounts the whole tree). Matches
    // the existing "0%, not NaN%" test's own pattern below, which proves a populated Doughnut
    // alone renders fine.
    vi.mocked(reportsApi.availableMonths).mockReset().mockResolvedValue([]);
    vi.mocked(reportsApi.forMonth).mockReset();
    vi.mocked(recurringApi.list).mockReset().mockResolvedValue([]);
  });

  it('shows the callout with the real amount/count/pct when the warning is active', async () => {
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary({
      spendByCategory: { Other: 73306, Shopping: 16627, Groceries: 193 },
      categoryReviewWarning: true, categoryReviewSpendPct: 81, categoryReviewSpendAmount: 73306,
      categoryReviewTransactionCount: 24,
    }));
    renderDashboard();

    expect(await screen.findByText('Spending needs category review')).toBeInTheDocument();
    expect(screen.getByText(/₹73,306 \(81%\) across 24 transactions/)).toBeInTheDocument();
    expect(screen.getByText('Review transactions →').closest('a')).toHaveAttribute('href', '/app/transactions');
  });

  it('uses singular wording for exactly one flagged transaction', async () => {
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary({
      spendByCategory: { Other: 500 },
      categoryReviewWarning: true, categoryReviewSpendPct: 100, categoryReviewSpendAmount: 500,
      categoryReviewTransactionCount: 1,
    }));
    renderDashboard();

    expect(await screen.findByText(/across 1 transaction this/)).toBeInTheDocument();
  });

  it('stays hidden when the warning is not active, even with real spending data', async () => {
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary({
      spendByCategory: { Groceries: 5000, Dining: 2000 },
      categoryReviewWarning: false, categoryReviewSpendPct: 5,
    }));
    renderDashboard();

    await screen.findByText('Spending Breakdown');
    expect(screen.queryByText('Spending needs category review')).not.toBeInTheDocument();
  });
});

// Task 14: Recent Transactions used to look up its icon/color from a 4-entry hardcoded map keyed
// by category NAME (Dining/Shopping/Transport/Salary), falling back to a generic ShoppingBag/gray
// for every other category -- including all 21 other default categories and any custom one a user
// creates. This confirms the row now renders the real icon/color TOKEN the backend assigned via
// categoryId, for a category that was never in that old 4-entry map.
describe('Dashboard — Recent Transactions icon/color', () => {
  beforeEach(() => {
    vi.mocked(dashboardApi.summary).mockReset().mockResolvedValue(summary());
    vi.mocked(dashboardApi.journey).mockReset().mockResolvedValue({ milestones: [] });
    vi.mocked(accountsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(categoriesApi.list).mockReset().mockResolvedValue([
      { id: 'cat-pets', name: 'Pets', isSystem: true, icon: 'paw-print', color: 'teal' } as any,
    ]);
    vi.mocked(transactionsApi.search).mockReset().mockResolvedValue({
      content: [
        {
          id: 'txn-1', accountId: 'acct-1', categoryId: 'cat-pets', categoryName: 'Pets',
          date: '2026-08-20', description: 'Vet visit', merchant: 'Local Vet Clinic',
          paymentMethod: 'UPI', amount: 1200, type: 'EXPENSE', tags: [], notes: null,
          reconciliationStatus: 'OK', recurring: false, needsCategoryReview: false,
          categoryManuallySet: false,
        },
      ],
      page: 0, size: 4, totalElements: 1, totalPages: 1,
    });
    vi.mocked(goalsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(insightsApi.get).mockReset().mockResolvedValue({ sentences: [], movers: [] });
    vi.mocked(userApi.get).mockReset().mockResolvedValue({
      email: 'amy@example.test', fullName: 'Amy Santiago', lowBalanceThreshold: 2000,
      theme: 'system', timezone: 'Asia/Kolkata', phoneNumber: '+919876500000',
      phoneVerified: true, createdAt: '2026-01-01T00:00:00Z', passwordChangedAt: null, signInMethod: 'PASSWORD',
    });
    vi.mocked(budgetsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(reportsApi.availableMonths).mockReset().mockResolvedValue(['2026-08']);
    vi.mocked(reportsApi.forMonth).mockReset().mockResolvedValue({
      month: '2026-08', income: 80000, expense: 45000, categories: [],
    });
    vi.mocked(recurringApi.list).mockReset().mockResolvedValue([]);
  });

  it("renders the category's real backend icon/color token instead of the old hardcoded fallback", async () => {
    renderDashboard();

    const row = (await screen.findByText('Vet visit')).closest('.flex.items-center.gap-3') as HTMLElement;
    // The teal token's real hex (CategoryPalette.COLORS / COLOR_HEX) rendered as `color + '20'`
    // (12.5% alpha) -- jsdom normalizes the inline style's hex+alpha shorthand to rgba -- not the
    // old generic '#262A33' fallback that every non-mapped category used to get.
    const iconWrap = row.querySelector('div[style*="background"]') as HTMLElement;
    expect(iconWrap.style.background).toBe('rgba(13, 148, 136, 0.125)');
  });
});

describe('Dashboard — Limited History Banner', () => {
  beforeEach(() => {
    vi.mocked(dashboardApi.summary).mockReset().mockResolvedValue(summary());
    vi.mocked(dashboardApi.journey).mockReset().mockResolvedValue({ milestones: [] });
    vi.mocked(accountsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(categoriesApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(transactionsApi.search).mockReset().mockResolvedValue({
      content: [], page: 0, size: 4, totalElements: 12, totalPages: 3,
    });
    vi.mocked(goalsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(insightsApi.get).mockReset().mockResolvedValue({ sentences: [], movers: [] });
    vi.mocked(userApi.get).mockReset().mockResolvedValue({
      email: 'amy@example.test', fullName: 'Amy Santiago', lowBalanceThreshold: 2000,
      theme: 'system', timezone: 'Asia/Kolkata', phoneNumber: '+919876500000',
      phoneVerified: true, createdAt: '2026-01-01T00:00:00Z', passwordChangedAt: null, signInMethod: 'PASSWORD',
    });
    vi.mocked(budgetsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(reportsApi.availableMonths).mockReset().mockResolvedValue(['2026-08']);
    vi.mocked(reportsApi.forMonth).mockReset().mockResolvedValue({
      month: '2026-08', income: 80000, expense: 45000, categories: [],
    });
    vi.mocked(recurringApi.list).mockReset().mockResolvedValue([]);
  });

  it('shows the banner with the real counts when history is limited', async () => {
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary({
      limitedHistory: true, historyMonthCount: 1, limitedHistoryMonthFloor: 3,
      statementCount: 2, accountCount: 2,
    }));
    renderDashboard();

    expect(await screen.findByText('Limited financial history')).toBeInTheDocument();
    expect(screen.getByText(
      'Based on 2 statements across 2 accounts and 1 month of activity. Trends and the Financial Health Score below may be unreliable until at least 3 months of history are imported.'
    )).toBeInTheDocument();
  });

  it('does not show the banner once history clears the floor', async () => {
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary({ limitedHistory: false }));
    renderDashboard();

    await screen.findByText('Financial Health Score'); // wait for the dashboard to finish loading
    expect(screen.queryByText('Limited financial history')).not.toBeInTheDocument();
  });

  it('uses singular wording for exactly one statement/account/month', async () => {
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary({
      limitedHistory: true, historyMonthCount: 1, limitedHistoryMonthFloor: 3,
      statementCount: 1, accountCount: 1,
    }));
    renderDashboard();

    expect(await screen.findByText(
      'Based on 1 statement across 1 account and 1 month of activity. Trends and the Financial Health Score below may be unreliable until at least 3 months of history are imported.'
    )).toBeInTheDocument();
  });

  it('stays hidden for a zero-transaction account -- the empty state covers that case on its own', async () => {
    vi.mocked(transactionsApi.search).mockResolvedValue({
      content: [], page: 0, size: 4, totalElements: 0, totalPages: 0,
    });
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary({
      limitedHistory: true, historyMonthCount: 0, statementCount: 0, accountCount: 0,
    }));
    renderDashboard();

    await screen.findByText('No transactions yet'); // Recent Transactions' own per-section empty state
    expect(screen.queryByText('Limited financial history')).not.toBeInTheDocument();
  });
});

describe('Dashboard — Subscriptions & Recurring Payments', () => {
  beforeEach(() => {
    vi.mocked(dashboardApi.summary).mockReset().mockResolvedValue(summary());
    // Empty by default -- FinancialJourney (a separate component with its own dedicated test
    // file) renders nothing for an empty milestone list, so this stays out of the way of every
    // assertion below unless a test explicitly cares about it.
    vi.mocked(dashboardApi.journey).mockReset().mockResolvedValue({ milestones: [] });
    vi.mocked(accountsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(categoriesApi.list).mockReset().mockResolvedValue([]);
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
      phoneVerified: true, createdAt: '2026-01-01T00:00:00Z', passwordChangedAt: null, signInMethod: 'PASSWORD',
    });
    vi.mocked(budgetsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(reportsApi.availableMonths).mockReset().mockResolvedValue(['2026-08']);
    vi.mocked(reportsApi.forMonth).mockReset().mockResolvedValue({
      month: '2026-08', income: 80000, expense: 45000, categories: [],
    });
    // Only `Date` is faked (not timers) -- RTL's findByText/waitFor poll via real setTimeout,
    // and faking those too would hang every `await screen.findByText(...)` below. Freezing "now"
    // makes these day-count assertions deterministic instead of drifting with whatever moment
    // `npm test` happens to run at.
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(2026, 7, 17, 12, 0, 0));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  // Built from local date components, not `.toISOString()` -- Dashboard's own expectedLabel()
  // parses `nextEstimate` as a local date (`new Date(dateStr + 'T00:00:00')`, no 'Z') and compares
  // it against local midnight. A UTC-sliced string here would silently disagree with that by a day
  // whenever the machine's timezone offset straddles midnight differently than UTC does -- which
  // is exactly what made these two tests fail on a real IST machine while passing under UTC CI.
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
    // Empty by default -- FinancialJourney (a separate component with its own dedicated test
    // file) renders nothing for an empty milestone list, so this stays out of the way of every
    // assertion below unless a test explicitly cares about it.
    vi.mocked(dashboardApi.journey).mockReset().mockResolvedValue({ milestones: [] });
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
      phoneVerified: true, createdAt: '2026-01-01T00:00:00Z', passwordChangedAt: null, signInMethod: 'PASSWORD',
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

  /**
   * Bug 44. The empty-state gate above only catches categoryEntries.length === 0 -- a completely
   * empty spendByCategory. It doesn't catch categories that exist but all sum to zero, which skips
   * the empty state, leaves totalSpend at 0, and divides val / totalSpend as 0/0 -- rendering the
   * literal string "NaN%" per category instead of a sane 0%.
   */
  it('shows 0%, not NaN%, when every category in the breakdown has a zero amount', async () => {
    vi.mocked(dashboardApi.summary).mockReset().mockResolvedValue(
      summary({ spendByCategory: { Groceries: 0, Rent: 0 } })
    );

    renderDashboard();

    await screen.findByText('Groceries');
    expect(screen.queryByText(/NaN/)).not.toBeInTheDocument();
    expect(screen.getAllByText('0%')).toHaveLength(2);
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

// D-25 PR3-C. FinancialJourney itself is unit-tested in its own file
// (components/FinancialJourney.test.tsx) -- this just confirms Dashboard actually renders it,
// and does so even in the per-section-empty-states scenario above (unlike Financial Health
// Score, which that describe block asserts is HIDDEN under the same isEmpty condition).
describe('Dashboard — Your Financial Journey', () => {
  beforeEach(() => {
    vi.mocked(dashboardApi.summary).mockReset().mockResolvedValue(summary());
    vi.mocked(dashboardApi.journey).mockReset().mockResolvedValue({
      milestones: [
        { type: 'ACCOUNT_CREATED', completed: true, completedAt: '2026-08-01T00:00:00Z' },
        { type: 'FIRST_IMPORT', completed: false, completedAt: null },
        { type: 'FIRST_BUDGET', completed: false, completedAt: null },
        { type: 'FIRST_GOAL', completed: false, completedAt: null },
        { type: 'FIRST_GOAL_ACHIEVED', completed: false, completedAt: null },
      ],
    });
    vi.mocked(accountsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(categoriesApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(transactionsApi.search).mockReset().mockResolvedValue({
      content: [], page: 0, size: 4, totalElements: 0, totalPages: 0,
    });
    vi.mocked(goalsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(insightsApi.get).mockReset().mockResolvedValue({ sentences: [], movers: [] });
    vi.mocked(userApi.get).mockReset().mockResolvedValue({
      email: 'amy@example.test', fullName: 'Amy Santiago', lowBalanceThreshold: 2000,
      theme: 'system', timezone: 'Asia/Kolkata', phoneNumber: '+919876500000',
      phoneVerified: true, createdAt: '2026-01-01T00:00:00Z', passwordChangedAt: null, signInMethod: 'PASSWORD',
    });
    vi.mocked(budgetsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(reportsApi.availableMonths).mockReset().mockResolvedValue([]);
    vi.mocked(recurringApi.list).mockReset().mockResolvedValue([]);
  });

  it('renders even with zero transactions, unlike Financial Health Score which hides in the same state', async () => {
    renderDashboard();

    expect(await screen.findByText('Your Financial Journey')).toBeInTheDocument();
    expect(screen.getByText('1 of 5 complete')).toBeInTheDocument();
    expect(screen.queryByText('Financial Health Score')).not.toBeInTheDocument();
  });
});
