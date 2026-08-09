import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { DashboardScreen } from './DashboardScreen';
import {
  accountsApi, dashboardApi, goalsApi, insightsApi, reportsApi, transactionsApi, userApi,
} from '../api/endpoints';
import type { DashboardSummary } from '../types';

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
