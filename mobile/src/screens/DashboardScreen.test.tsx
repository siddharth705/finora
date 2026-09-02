import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { Dimensions, RefreshControl } from 'react-native';
import { QueryClient, QueryClientProvider, onlineManager } from '@tanstack/react-query';
import { DashboardScreen } from './DashboardScreen';
import {
  accountsApi, budgetsApi, dashboardApi, goalsApi, insightsApi, reportsApi, transactionsApi, userApi,
} from '../api/endpoints';
import type { DashboardSummary } from '../types';

// useWindowDimensions (used for chart width and, per the "large Dynamic Type" describe block
// below, font scale) reads its value from Dimensions.get('window') on mount -- spying there,
// rather than re-mocking the whole 'react-native' module, avoids re-running the module's own
// native TurboModule getters (which blow up under jest-expo when the module object is spread
// rather than used as-is).
const dimensionsGetSpy = jest.spyOn(Dimensions, 'get');

/**
 * The Expenses KPI renders through AnimatedNumber now -- a non-editable TextInput, so its
 * settled value lives in `defaultValue` (see AnimatedNumber's own doc comment) rather than in
 * text content getByText can see. These cross-checks against DonutChart's plain-Text centre
 * label predate that change; kept accurate by reading each source the way it actually renders.
 */
function expensesKpiValue(): string {
  return screen.getByTestId('kpi-Expenses').props.defaultValue as string;
}

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
  transactionsApi: { search: jest.fn(), needsReview: jest.fn(), needsReviewGroups: jest.fn() },
  goalsApi: { list: jest.fn() },
  insightsApi: { get: jest.fn() },
  userApi: { get: jest.fn() },
  reportsApi: { availableMonths: jest.fn(), forMonth: jest.fn() },
  budgetsApi: { list: jest.fn() },
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
const budgets = budgetsApi as jest.Mocked<typeof budgetsApi>;

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
  // usePrefetchAdjacentScreens prefetches budgets unconditionally on every mount (see that hook's
  // own file) -- without a default here, every test below it triggers React Query's own "Query
  // data cannot be undefined" console.error for the ['budgets'] key, since the un-mocked jest.fn()
  // resolves to undefined.
  budgets.list.mockResolvedValue([]);
  // Default: an empty review backlog, so the nudge stays absent unless a test asks for it.
  transactions.needsReview.mockResolvedValue([]);
  transactions.needsReviewGroups.mockResolvedValue([]);
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
   * NOTE -- this suite's original premise no longer holds, and the fixture below is what keeps it
   * true here. It used to read: "the backend builds spendByCategory and monthlyExpense from the
   * same filtered transaction list, so their totals agree by construction". PR #596 (2026-08-30)
   * ended that: DashboardService now feeds monthlyExpense from
   * `RefundNetting.excludingInvestmentTransfers(active)` while spendByCategory still streams the
   * unfiltered list, so in any real month containing a SIP or a broker debit the category sum is
   * LARGER than monthlyExpense. This fixture sets the two equal by hand, so these tests still pass
   * -- they simply no longer describe production.
   *
   * What they DO still protect is the bug they were written for: the centre must show the whole
   * period total, not just the six slices that fit. That is unaffected. The open question they no
   * longer answer is which figure the centre should claim when the two genuinely disagree -- the
   * screen currently shows "TOTAL ₹35,500" in the donut next to "Expenses ₹32,500" in the KPI, with
   * nothing distinguishing the two definitions. That is a product call, deliberately not made here.
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
    // The centre label is still a plain Text; the Expenses KPI now renders through AnimatedNumber
    // (see expensesKpiValue's own comment) -- both must agree on the true total.
    expect(screen.getByText('₹35,500')).toBeTruthy();
    expect(expensesKpiValue()).toBe('₹35,500');
  });

  it('agrees with the Expenses KPI, which reads the same backend field', async () => {
    // Two figures for one quantity on one screen is the failure mode worth pinning: whichever is
    // wrong, a user cannot tell which to believe.
    dashboard.summary.mockResolvedValue(
      emptySummary({ spendByCategory: CATEGORIES, monthlyExpense: TRUE_TOTAL })
    );

    renderScreen();
    await screen.findByText('Total Balance');

    expect(screen.getByText('₹35,500')).toBeTruthy();
    expect(expensesKpiValue()).toBe('₹35,500');
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
    expect(screen.getByText('₹39,000')).toBeTruthy();
    expect(expensesKpiValue()).toBe('₹39,000');
  });

  it('is unaffected when every category already fits', async () => {
    // Guards the fix from over-reaching: with six or fewer categories nothing was ever wrong, and
    // the displayed total must stay exactly what it was.
    dashboard.summary.mockResolvedValue(
      emptySummary({ spendByCategory: { Rent: 20000, Food: 5000 }, monthlyExpense: 25000 })
    );

    renderScreen();
    await screen.findByText('Total Balance');

    expect(screen.getByText('₹25,000')).toBeTruthy();
    expect(expensesKpiValue()).toBe('₹25,000');
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

/**
 * Cash Flow is fed by its own two-step chain -- report-months, then one report query per month --
 * which the card used to render nothing about: its only gate was `summary`, an unrelated query.
 * That made every failure and every intermediate state indistinguishable from "you have no data".
 */
describe('Cash Flow loading and failure states', () => {
  afterEach(() => onlineManager.setOnline(true));

  const summaryOnly = () => {
    dashboard.summary.mockResolvedValue(emptySummary());
  };

  it('does not claim there is no monthly data while the months are still loading', async () => {
    // `summary` resolves; the months list never does. This is the ordinary cold-start ordering,
    // not an error case -- the two requests are sequential, so this window happens on every launch.
    dashboard.summary.mockResolvedValue(emptySummary({ currentBalance: 4200 }));
    reports.availableMonths.mockReturnValue(new Promise(() => {}) as never);

    renderScreen();

    // Anchored on the KPI section, which only renders once summary has landed -- not on the static
    // "Cash Flow" heading, which is present during the skeleton state too and would let this
    // assertion run before summary arrived, passing without ever entering the window it tests.
    // (The balance itself goes through AnimatedNumber, so it is not a queryable Text node.)
    await screen.findByText('Total Balance');

    expect(screen.queryByText(/No monthly data yet/i)).toBeNull();
  });

  it('says it could not load the cash flow rather than showing an empty chart', async () => {
    summaryOnly();
    reports.availableMonths.mockResolvedValue(['2026-07', '2026-08']);
    reports.forMonth.mockRejectedValue(new Error('boom'));

    renderScreen();

    expect(await screen.findByText(/Couldn’t load your cash flow/)).toBeTruthy();
    expect(screen.queryByText(/No monthly data yet/i)).toBeNull();
  });

  it('admits when only some months are missing instead of drawing the gap as continuous', async () => {
    summaryOnly();
    reports.availableMonths.mockResolvedValue(['2026-06', '2026-07', '2026-08']);
    reports.forMonth.mockImplementation((month: string) =>
      month === '2026-07'
        ? Promise.reject(new Error('boom'))
        : Promise.resolve({ month, income: 100, expense: 50, categories: [] })
    );

    renderScreen();

    // The chart still renders what it has -- but says what it doesn't have, because the x-axis is
    // index-based and would otherwise join June straight to August as one even segment.
    expect(await screen.findByText(/One month couldn’t be loaded/)).toBeTruthy();
  });

  it('does not spin a skeleton forever when offline with no cached months', async () => {
    // The realistic offline shape, not a blanket one: 'dashboard-summary' IS in the persistence
    // allowlist, so it warm-starts from disk, while a device that has never loaded this month's
    // report list has nothing for 'report-months'. That query then PAUSES rather than failing --
    // pending, and staying pending until the network returns. Gating the skeleton on isPending
    // alone would trade the old false empty state for a spinner that implies data is coming.
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false, gcTime: 0 } },
    });
    queryClient.setQueryData(['dashboard-summary'], emptySummary({ currentBalance: 4200 }));
    onlineManager.setOnline(false);

    render(
      <QueryClientProvider client={queryClient}>
        <DashboardScreen />
      </QueryClientProvider>
    );

    expect(await screen.findByText(/Couldn’t load your cash flow/)).toBeTruthy();
    expect(screen.queryByText(/No monthly data yet/i)).toBeNull();
  });

  it('still shows the genuine empty state when there really are no months', async () => {
    // The other side of the guard: a user with no statements at all must keep getting the real
    // answer rather than an error.
    summaryOnly();
    reports.availableMonths.mockResolvedValue([]);

    renderScreen();

    expect(await screen.findByText(/No monthly data yet/i)).toBeTruthy();
    expect(screen.queryByText(/Couldn’t load your cash flow/)).toBeNull();
  });
});

describe('adjacent-screen prefetching', () => {
  it('prefetches the Ledger, Budgets and latest Reports caches once summary loads', async () => {
    dashboard.summary.mockResolvedValue(emptySummary());
    reports.availableMonths.mockResolvedValue(['2026-08']);
    reports.forMonth.mockResolvedValue({ month: '2026-08', income: 0, expense: 0, categories: [] });

    renderScreen();

    // Asserted via the mock call, not queryClient.getQueryData(['budgets']): this screen's test
    // QueryClient uses gcTime: 0 (see renderScreen's own comment), and a prefetched query has no
    // mounted useQuery observer, so it goes "inactive" -- and eligible for garbage collection --
    // the instant it resolves. The prefetch demonstrably still ran and populated the cache
    // correctly (confirmed manually during development), but the cache entry doesn't survive long
    // enough for a read-back assertion here to reliably observe it.
    await waitFor(() => expect(budgets.list).toHaveBeenCalled());
    expect(transactions.search).toHaveBeenCalledWith({ page: 0, size: 20, sortField: 'date', sortDir: 'desc' });
    await waitFor(() => expect(reports.forMonth).toHaveBeenCalledWith('2026-08'));
  });
});

/**
 * The review nudge.
 *
 * The categorization design spec (§3) is explicit that "needs review" is a queue state and never a
 * chart slice -- a wedge of unclassified spend sitting in the donut alongside Food and Travel
 * reads as information about someone's money when it is actually an admission of not knowing. So
 * the backlog surfaces here as a count of work with somewhere to go, above the figures it would
 * otherwise quietly distort.
 */
describe('categorization review nudge', () => {
  beforeEach(() => {
    dashboard.summary.mockResolvedValue(emptySummary());
  });

  it('stays absent when there is nothing to review', async () => {
    renderScreen();
    await screen.findByTestId('kpi-Expenses');
    expect(screen.queryByText(/needs? a quick look/i)).toBeNull();
  });

  it('counts the one-off queue and every transaction inside every merchant group', async () => {
    // The two queries are disjoint server-side, so the honest total is the sum -- showing only
    // one of them would understate the user's actual backlog.
    transactions.needsReview.mockResolvedValue([{ id: 't-1' }, { id: 't-2' }] as never);
    transactions.needsReviewGroups.mockResolvedValue([
      { merchantId: 'm-1', merchantName: 'Swiggy', transactionIds: ['t-3', 't-4', 't-5'] },
    ] as never);

    renderScreen();

    expect(await screen.findByText('5 transactions need a quick look')).toBeTruthy();
  });

  it('uses the singular for a backlog of one', async () => {
    transactions.needsReview.mockResolvedValue([{ id: 't-1' }] as never);

    renderScreen();

    expect(await screen.findByText('1 transaction needs a quick look')).toBeTruthy();
  });

  it('renders the rest of the dashboard when the backlog lookup fails', async () => {
    // A nudge is the one thing on this screen that should fail silently: no count means no nudge,
    // which is exactly what a user with an empty queue already sees.
    transactions.needsReview.mockRejectedValue(new Error('down'));
    transactions.needsReviewGroups.mockRejectedValue(new Error('down'));

    renderScreen();

    await screen.findByTestId('kpi-Expenses');
    expect(screen.queryByText(/needs? a quick look/i)).toBeNull();
  });
});
