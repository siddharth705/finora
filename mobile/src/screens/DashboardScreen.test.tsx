import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { Dimensions, RefreshControl } from 'react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { DashboardScreen } from './DashboardScreen';
import {
  accountsApi, dashboardApi, goalsApi, insightsApi, reportsApi, transactionsApi, userApi,
} from '../api/endpoints';
import type { DashboardSummary } from '../types';

// useWindowDimensions (used for chart width and, per the "large Dynamic Type" describe block
// below, font scale) reads its value from Dimensions.get('window') on mount -- spying there,
// rather than re-mocking the whole 'react-native' module, avoids re-running the module's own
// native TurboModule getters (which blow up under jest-expo when the module object is spread
// rather than used as-is).
const dimensionsGetSpy = jest.spyOn(Dimensions, 'get');

/**
 * The distinction this file exists to protect: a dashboard that FAILED TO LOAD must never be
 * indistinguishable from a dashboard that legitimately has no money in it.
 *
 * Both render mostly-zero data, so the difference lives entirely in one guard --
 * `if (!summary)` in DashboardScreen. Delete that guard and every test here still compiles, the
 * screen still renders, and a user whose request 500s is told their balance is Rs 0. In a finance
 * app that is not a cosmetic bug: it is the app asserting something false about someone's money.
 *
 * The behaviour was correct before these tests were written (the guard has been there since the
 * screen was built). What was missing was anything stopping a refactor from removing it, which is
 * what these tests supply -- they pin the DIFFERENCE, not merely the failure path.
 */

jest.mock('../api/endpoints', () => ({
  dashboardApi: { summary: jest.fn() },
  accountsApi: { list: jest.fn() },
  transactionsApi: { search: jest.fn() },
  goalsApi: { list: jest.fn() },
  insightsApi: { get: jest.fn() },
  userApi: { get: jest.fn() },
  reportsApi: { availableMonths: jest.fn(), forMonth: jest.fn() },
}));

jest.mock('../context/AuthContext', () => ({
  useAuth: () => ({ fullName: 'Test User' }),
}));

const dashboard = dashboardApi as jest.Mocked<typeof dashboardApi>;
const accounts = accountsApi as jest.Mocked<typeof accountsApi>;
const transactions = transactionsApi as jest.Mocked<typeof transactionsApi>;
const goals = goalsApi as jest.Mocked<typeof goalsApi>;
const insights = insightsApi as jest.Mocked<typeof insightsApi>;
const user = userApi as jest.Mocked<typeof userApi>;
const reports = reportsApi as jest.Mocked<typeof reportsApi>;

/**
 * A real summary for an account that has been imported but holds nothing -- every figure zero,
 * every delta null, no categories. This is the LEGITIMATE empty case, and it is deliberately the
 * closest possible neighbour to the failure case: if the screen ever conflates the two, this is
 * the fixture that catches it.
 */
function emptySummary(over: Partial<DashboardSummary> = {}): DashboardSummary {
  return {
    currentBalance: 0,
    totalAssets: 0,
    totalLiabilities: 0,
    netWorth: 0,
    monthlyIncome: 0,
    monthlyExpense: 0,
    netCashFlow: 0,
    savingsRatePct: 0,
    incomeDeltaPct: null,
    expenseDeltaPct: null,
    netDeltaPct: null,
    healthScore: 0,
    healthLabel: 'No data',
    healthBreakdown: {},
    healthScoreAvailable: false,
    healthScoreTransactionCount: 0,
    healthScoreMinTransactions: 10,
    spendByCategory: {},
    notifications: [],
    reportingMonth: null,
    reportingMonthIsCurrent: true,
    ...over,
  } as DashboardSummary;
}

function renderScreen() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  });
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <DashboardScreen />
    </QueryClientProvider>
  );
  return { ...utils, queryClient };
}

beforeEach(() => {
  jest.clearAllMocks();
  // Reset to the default (non-scaled) window on every test -- a leftover large fontScale from one
  // test must never leak into the next.
  dimensionsGetSpy.mockReturnValue({ width: 390, height: 844, scale: 2, fontScale: 1 });
  // Everything except the summary succeeds, so each test isolates one variable: the summary call.
  accounts.list.mockResolvedValue([]);
  transactions.search.mockResolvedValue({
    content: [], page: 0, size: 5, totalElements: 0, totalPages: 0,
  } as never);
  goals.list.mockResolvedValue([]);
  insights.get.mockResolvedValue({ sentences: [], movers: [] } as never);
  user.get.mockResolvedValue({ timezone: 'Asia/Kolkata' } as never);
  reports.availableMonths.mockResolvedValue([]);
  reports.forMonth.mockResolvedValue({} as never);
});

describe('when /dashboard/summary fails', () => {
  it('says the dashboard could not be loaded, instead of rendering it', async () => {
    dashboard.summary.mockRejectedValue(new Error('Network Error'));

    renderScreen();

    expect(await screen.findByText(/Couldn't load your dashboard/i)).toBeTruthy();
    // The load failed, so the screen must not also claim anything about the user's money.
    expect(screen.queryByText('Total Balance')).toBeNull();
    expect(screen.queryByText('Net Savings')).toBeNull();
  });

  it('offers a retry that refetches rather than leaving the user stuck', async () => {
    dashboard.summary.mockRejectedValueOnce(new Error('Network Error'));
    await screen.findByText; // no-op guard for lint symmetry

    renderScreen();
    await screen.findByText(/Couldn't load your dashboard/i);
    expect(dashboard.summary).toHaveBeenCalledTimes(1);

    dashboard.summary.mockResolvedValue(emptySummary({ currentBalance: 4200 }));
    fireEvent.press(screen.getByText(/Try again/i));

    // The retry must actually re-request; a button that only re-renders the error is worse than none.
    await waitFor(() => expect(dashboard.summary).toHaveBeenCalledTimes(2));
    expect(await screen.findByText('Total Balance')).toBeTruthy();
  });

  it('does not render a zero balance while the request is failing', async () => {
    // The specific misreading this guards: Rs 0 shown as fact when the number is simply unknown.
    dashboard.summary.mockRejectedValue(new Error('500'));

    renderScreen();
    await screen.findByText(/Couldn't load your dashboard/i);

    expect(screen.queryByText('₹0')).toBeNull();
    expect(screen.queryByText('₹0.00')).toBeNull();
  });
});

describe('M0-A: the spending donut must not understate the period total', () => {
  /**
   * A known corpus, not a plausible-looking one. Eight categories, because the donut has six
   * colours and the interesting case is the seventh:
   *
   *   Rent 20,000 + Food 5,000 + Transport 3,000 + Bills 2,500 + Shopping 2,000
   *     + Health 1,500 + Education 800 + Misc 700  =  35,500
   *   top six only                                 =  34,000
   *
   * The backend builds spendByCategory and monthlyExpense from the same filtered transaction list
   * (DashboardService.java:104 and the expenseCur it shares), so their totals agree by
   * construction: 35,500 is the authoritative figure for the period, and any smaller number shown
   * as a spend total is wrong rather than merely rounded.
   */
  const CATEGORIES = {
    Rent: 20000, Food: 5000, Transport: 3000, Bills: 2500,
    Shopping: 2000, Health: 1500, Education: 800, Misc: 700,
  };
  const TRUE_TOTAL = 35500;

  it('shows the whole period total in the centre, not just the slices that fit', async () => {
    dashboard.summary.mockResolvedValue(
      emptySummary({ spendByCategory: CATEGORIES, monthlyExpense: TRUE_TOTAL })
    );

    renderScreen();
    await screen.findByText('Total Balance');

    // ₹34,000 is the sum of the six largest categories. Rendering it as the centre of a chart
    // titled "Spending by Category" tells the user they spent 1,500 less than they did.
    expect(screen.queryByText('₹34,000')).toBeNull();
    // getAllByText, not getByText: the correct total legitimately appears more than once (the
    // centre and the Expenses KPI), and the next test asserts exactly that agreement.
    expect(screen.getAllByText('₹35,500').length).toBeGreaterThan(0);
  });

  it('agrees with the Expenses KPI, which reads the same backend field', async () => {
    // Two figures for one quantity on one screen is the failure mode worth pinning: whichever is
    // wrong, a user cannot tell which to believe.
    dashboard.summary.mockResolvedValue(
      emptySummary({ spendByCategory: CATEGORIES, monthlyExpense: TRUE_TOTAL })
    );

    renderScreen();
    await screen.findByText('Total Balance');

    expect(screen.getAllByText('₹35,500').length).toBeGreaterThanOrEqual(2);
  });

  it('does not show two rows both labelled Other', async () => {
    /**
     * Found on a real Android device, in the state an actual import produces: a CSV whose merchants
     * match no rule lands in a REAL backend category called "Other", and the remainder bucket is
     * called "Other" too. The legend rendered "Other 3,000" and "Other 5,500" as separate rows.
     * The total was right; two identically labelled rows with different amounts still is not
     * something a reader can resolve.
     *
     * Nine categories, with a real "Other" large enough to survive into the named five:
     *   Rent 20,000 · Food 5,000 · Transport 3,000 · Other 3,000 · Bills 2,500
     *   + Health 2,000 · Shopping 2,000 · Education 800 · Misc 700  =  39,000
     */
    dashboard.summary.mockResolvedValue(
      emptySummary({
        spendByCategory: {
          Rent: 20000, Food: 5000, Transport: 3000, Other: 3000, Bills: 2500,
          Health: 2000, Shopping: 2000, Education: 800, Misc: 700,
        },
        monthlyExpense: 39000,
      })
    );

    renderScreen();
    await screen.findByText('Total Balance');

    expect(screen.getAllByText('Other')).toHaveLength(1);
    // 3,000 real + 5,500 remainder, in one row rather than two.
    expect(screen.getByText('₹8,500')).toBeTruthy();
    expect(screen.queryByText('₹5,500')).toBeNull();
    // And the invariant that started all of this still holds.
    expect(screen.getAllByText('₹39,000').length).toBeGreaterThanOrEqual(2);
  });

  it('is unaffected when every category already fits', async () => {
    // Guards the fix from over-reaching: with six or fewer categories nothing was ever wrong, and
    // the displayed total must stay exactly what it was.
    dashboard.summary.mockResolvedValue(
      emptySummary({ spendByCategory: { Rent: 20000, Food: 5000 }, monthlyExpense: 25000 })
    );

    renderScreen();
    await screen.findByText('Total Balance');

    expect(screen.getAllByText('₹25,000').length).toBeGreaterThanOrEqual(2);
  });
});

describe('when the dashboard is legitimately empty', () => {
  it('renders the real dashboard, not the failure state', async () => {
    // A brand-new account with nothing imported: the request SUCCEEDED and the answer is zero.
    dashboard.summary.mockResolvedValue(emptySummary());

    renderScreen();

    expect(await screen.findByText('Total Balance')).toBeTruthy();
    expect(screen.getByText('Income')).toBeTruthy();
    expect(screen.getByText('Expenses')).toBeTruthy();
    expect(screen.getByText('Net Savings')).toBeTruthy();
    // The whole point: zero data is not an error, and must never be reported as one.
    expect(screen.queryByText(/Couldn't load your dashboard/i)).toBeNull();
    expect(screen.queryByText(/Try again/i)).toBeNull();
  });

  it('is reached through a different branch than the failure state', async () => {
    // Renders both cases in one test so the assertion is the DIFFERENCE itself. A refactor that
    // collapses "no summary" and "empty summary" into one path fails here even if each case looks
    // individually reasonable.
    dashboard.summary.mockResolvedValue(emptySummary());
    const ok = renderScreen();
    await screen.findByText('Total Balance');
    const emptyShowsError = screen.queryByText(/Couldn't load your dashboard/i) !== null;
    ok.unmount();

    dashboard.summary.mockRejectedValue(new Error('Network Error'));
    renderScreen();
    await screen.findByText(/Couldn't load your dashboard/i);
    const failureShowsKpis = screen.queryByText('Total Balance') !== null;

    expect(emptyShowsError).toBe(false);
    expect(failureShowsKpis).toBe(false);
  });
});

describe('large Dynamic Type support (mobile design review, iOS VoiceOver/Dynamic Type pass)', () => {
  // A financial description long enough to actually truncate at either line count -- short enough
  // fixtures would pass numberOfLines={1} by accident and prove nothing.
  const LONG_DESCRIPTION = 'Payment to Greenfield Grocers and Home Essentials Superstore Ltd';
  const LONG_GOAL_NAME = 'Emergency Fund for Home Repairs and Unexpected Medical Expenses';

  beforeEach(() => {
    transactions.search.mockResolvedValue({
      content: [{
        id: 't1', accountId: 'a1', categoryId: 'c1', categoryName: 'Shopping', date: '2026-08-01',
        description: LONG_DESCRIPTION, merchant: 'Greenfield Grocers', paymentMethod: 'CARD',
        amount: 1200, type: 'EXPENSE', tags: [], notes: null, reconciliationStatus: 'OK',
        recurring: false, needsCategoryReview: false, categoryManuallySet: false,
      }],
      page: 0, size: 5, totalElements: 1, totalPages: 1,
    } as never);
    goals.list.mockResolvedValue([
      { id: 'g1', name: LONG_GOAL_NAME, targetAmount: 100000, currentAmount: 25000 },
    ] as never);
  });

  it('truncates the transaction description and goal name to one line at the default text size', async () => {
    dashboard.summary.mockResolvedValue(emptySummary());
    renderScreen();

    const desc = await screen.findByText(LONG_DESCRIPTION);
    expect(desc.props.numberOfLines).toBe(1);

    const goalName = await screen.findByText(LONG_GOAL_NAME);
    expect(goalName.props.numberOfLines).toBe(1);
  });

  it('allows two lines instead of truncating once Dynamic Type is scaled up', async () => {
    dimensionsGetSpy.mockReturnValue({ width: 390, height: 844, scale: 2, fontScale: 1.3 });
    dashboard.summary.mockResolvedValue(emptySummary());
    renderScreen();

    const desc = await screen.findByText(LONG_DESCRIPTION);
    expect(desc.props.numberOfLines).toBe(2);

    const goalName = await screen.findByText(LONG_GOAL_NAME);
    expect(goalName.props.numberOfLines).toBe(2);
  });

  it('still allows two lines at full accessibility text sizes, not just the first large step', async () => {
    dimensionsGetSpy.mockReturnValue({ width: 390, height: 844, scale: 2, fontScale: 2.0 });
    dashboard.summary.mockResolvedValue(emptySummary());
    renderScreen();

    expect((await screen.findByText(LONG_DESCRIPTION)).props.numberOfLines).toBe(2);
    expect((await screen.findByText(LONG_GOAL_NAME)).props.numberOfLines).toBe(2);
  });
});

describe('pull-to-refresh indicator', () => {
  it('does not wait on accounts, since accounts data is never rendered on this screen', async () => {
    dashboard.summary.mockResolvedValue(emptySummary());
    const { queryClient } = renderScreen();
    await screen.findByText('Total Balance');

    // Summary resolves fast; accounts is held open on purpose -- the indicator must NOT wait on
    // it, since nothing on screen reads accountsQ.data. (This used to be inverted: the indicator
    // tracked accountsQ.isFetching, which both kept the spinner up after every visible section had
    // settled, AND could flip the spinner on with no user gesture at all if accounts merely
    // happened to resolve slower than summary/recent-transactions on first mount.)
    let resolveAccounts: (value: unknown) => void = () => {};
    accounts.list.mockReturnValue(new Promise((resolve) => { resolveAccounts = resolve as typeof resolveAccounts; }));

    await act(async () => {
      void queryClient.invalidateQueries({ queryKey: ['dashboard-summary'] });
      void queryClient.invalidateQueries({ queryKey: ['accounts'] });
    });

    // The QueryClient's own state flips to fetching synchronously inside invalidateQueries, but
    // the component's re-render (via the query observer's subscriber) can land a tick later than
    // act()'s own flush -- waitFor absorbs that gap instead of asserting on a stale render.
    await waitFor(() => {
      expect(screen.UNSAFE_getByType(RefreshControl).props.refreshing).toBe(false);
    });

    await act(async () => resolveAccounts([]));
  });

  it('stays visible until Goals, Insights, and the Cash Flow report queries have finished too', async () => {
    dashboard.summary.mockResolvedValue(emptySummary());
    const { queryClient } = renderScreen();
    await screen.findByText('Total Balance');

    // Summary/accounts/recent-transactions all resolve fast; insights is held open on purpose --
    // refresh() invalidates it and its section is genuinely rendered, so the spinner must track it.
    let resolveInsights: (value: unknown) => void = () => {};
    insights.get.mockReturnValue(new Promise((resolve) => { resolveInsights = resolve as typeof resolveInsights; }));

    await act(async () => {
      void queryClient.invalidateQueries({ queryKey: ['dashboard-summary'] });
      void queryClient.invalidateQueries({ queryKey: ['insights'] });
    });

    await waitFor(() => {
      expect(screen.UNSAFE_getByType(RefreshControl).props.refreshing).toBe(true);
    });

    await act(async () => resolveInsights({ sentences: [], movers: [] }));

    await waitFor(() => {
      expect(screen.UNSAFE_getByType(RefreshControl).props.refreshing).toBe(false);
    });
  });
});

describe('the shell mounts before the network settles (dashboard shell capstone)', () => {
  it('shows the greeting and section skeletons immediately, then swaps in real content once summary and recent transactions arrive', async () => {
    let resolveSummary: (value: unknown) => void = () => {};
    dashboard.summary.mockReturnValue(new Promise((resolve) => { resolveSummary = resolve as typeof resolveSummary; }));
    let resolveTxns: (value: unknown) => void = () => {};
    transactions.search.mockReturnValue(new Promise((resolve) => { resolveTxns = resolve as typeof resolveTxns; }));

    renderScreen();

    // The shell -- greeting and section headings -- is already on screen, not hidden behind a
    // full-screen spinner.
    expect(screen.getByText(/Good (morning|afternoon|evening|night)/)).toBeTruthy();
    expect(screen.getByText('Cash Flow')).toBeTruthy();
    expect(screen.getByText('Recent Transactions')).toBeTruthy();
    expect(screen.getAllByTestId('shimmer-block', { hidden: true }).length).toBeGreaterThan(0);
    expect(screen.queryByText('Total Balance')).toBeNull();

    await act(async () => {
      resolveSummary(emptySummary({ currentBalance: 4200 }));
      resolveTxns({ content: [], page: 0, size: 5, totalElements: 0, totalPages: 0 });
    });

    expect(await screen.findByText('Total Balance')).toBeTruthy();
    expect(screen.queryByTestId('shimmer-block', { hidden: true })).toBeNull();
  });

  it('skeletons Recent Transactions independently of the summary section', async () => {
    dashboard.summary.mockResolvedValue(emptySummary());
    let resolveTxns: (value: unknown) => void = () => {};
    transactions.search.mockReturnValue(new Promise((resolve) => { resolveTxns = resolve as typeof resolveTxns; }));

    renderScreen();

    expect(await screen.findByText('Total Balance')).toBeTruthy();
    // Summary has already settled, but the transactions section is still on its own skeleton.
    expect(screen.getAllByTestId('skeleton-transaction-row', { hidden: true }).length).toBeGreaterThan(0);

    await act(async () => resolveTxns({ content: [], page: 0, size: 5, totalElements: 0, totalPages: 0 }));

    expect(await screen.findByText(/No transactions yet/i)).toBeTruthy();
  });
});

describe('Recent Transactions error state', () => {
  it('says the transactions could not be loaded, instead of claiming there are none', async () => {
    dashboard.summary.mockResolvedValue(emptySummary());
    transactions.search.mockRejectedValue(new Error('boom'));
    renderScreen();

    expect(await screen.findByText(/Couldn't load your transactions/)).toBeTruthy();
    expect(screen.queryByText(/No transactions yet/i)).toBeNull();
  });
});
