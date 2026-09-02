import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider, onlineManager } from '@tanstack/react-query';
import { InvestmentsScreen } from './InvestmentsScreen';
import { accountsApi, networthApi } from '../api/endpoints';
import { light } from '../theme/palette';
import type { Account } from '../types';

jest.mock('../api/endpoints', () => ({
  accountsApi: { list: jest.fn(), create: jest.fn(), remove: jest.fn() },
  networthApi: { current: jest.fn(), saveSnapshot: jest.fn() },
}));

const accounts = accountsApi as jest.Mocked<typeof accountsApi>;
const networth = networthApi as jest.Mocked<typeof networthApi>;

const bank = {
  id: 'OTHER', officialName: null, shortName: 'Other', colorHex: '#000000', initials: 'OT',
  logoPath: '', category: null, websiteUrl: null, ifscPrefix: null, supportedAccountTypes: [],
};

function account(over: Partial<Account>): Account {
  return {
    id: 'a-1', name: 'Index fund', accountType: 'INVESTMENT', balance: 150000, bank,
    lastImportedAt: null, lastStatementPeriodStart: null, lastStatementPeriodEnd: null,
    statementsCount: 0, transactionsCount: 0, status: 'ACTIVE', ...over,
  };
}

const fixedDeposit = account({
  id: 'a-2',
  name: 'Fixed Deposit',
  balance: 200000,
  investmentKind: 'FD',
  principalAmount: 180000,
  interestRate: 7.1,
  maturityDate: '2027-03-01',
  maturityAmount: 220000,
});

function renderScreen() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <InvestmentsScreen />
    </QueryClientProvider>
  );
}

async function settle() {
  await act(async () => {});
}

/** Anchors on a heading rather than a value: amounts and holding names each appear twice on this
 *  screen by design -- once in the chart's own legend, once in the list. */
async function loaded() {
  await screen.findByText('Holdings');
}

describe('InvestmentsScreen', () => {
  beforeEach(() => {
    accounts.list.mockReset().mockResolvedValue([
      account({}),
      fixedDeposit,
      // A savings account must not appear here or count toward the total.
      account({ id: 'a-3', name: 'HDFC Savings', accountType: 'SAVINGS', balance: 999999 }),
    ]);
    accounts.create.mockReset().mockResolvedValue(account({}));
    accounts.remove.mockReset().mockResolvedValue(undefined as never);
    networth.current.mockReset().mockResolvedValue({
      totalAssets: 500000,
      totalLiabilities: 50000,
      netWorth: 450000,
      history: [
        { date: '2026-06-01', netWorth: 400000 },
        { date: '2026-07-01', netWorth: 450000 },
      ],
    });
    networth.saveSnapshot.mockReset().mockResolvedValue({
      totalAssets: 500000, totalLiabilities: 50000, netWorth: 450000, history: [],
    });
  });

  it('counts only investment accounts toward the total', async () => {
    renderScreen();
    await loaded();

    // 150,000 + 200,000 -- the savings account's 999,999 is not an investment. Twice on purpose:
    // the Investments card and the allocation chart's centre label are the same figure.
    expect(screen.getAllByText('₹3,50,000')).toHaveLength(2);
    expect(screen.queryByText('HDFC Savings')).toBeNull();
  });

  // What makes a deposit a deposit rather than a name and a balance.
  /**
   * Holding names are typed by the user, so two can genuinely be the same -- unlike the spend
   * categories the same chart renders on the Dashboard. Keying and colouring the arcs by label
   * collapsed both into one React key and painted the second with the first's colour.
   */
  it('renders two same-named holdings as two distinct slices', async () => {
    accounts.list.mockReset().mockResolvedValue([
      account({ id: 'a-1', name: 'Gold ETF', balance: 10000 }),
      account({ id: 'a-2', name: 'Gold ETF', balance: 30000 }),
    ]);
    const warn = jest.spyOn(console, 'error').mockImplementation(() => {});
    renderScreen();
    await loaded();

    // Two legend rows and two holding rows -- four in total, not two.
    expect(screen.getAllByText('Gold ETF')).toHaveLength(4);
    expect(warn.mock.calls.flat().join(' ')).not.toMatch(/same key|unique "key"/i);
    warn.mockRestore();
  });

  it('shows a deposit’s terms', async () => {
    renderScreen();

    const terms = await screen.findByText(/Principal ₹1,80,000/);
    expect(terms).toBeTruthy();
    expect(terms.props.children).toContain('7.1% p.a.');
    // Not 28 Feb: a LocalDate parsed through UTC would shift a day behind UTC.
    expect(terms.props.children).toContain('Matures 1 Mar 2027');
  });

  it('adds a holding with the value entered', async () => {
    renderScreen();
    await loaded();

    fireEvent.press(screen.getByText('+ Add'));
    await settle();
    fireEvent.changeText(screen.getByLabelText('Name'), 'Gold ETF');
    fireEvent.changeText(screen.getByLabelText('Current value'), '25000');
    fireEvent.press(screen.getByText('Add Holding'));
    await settle();

    await waitFor(() =>
      expect(accounts.create).toHaveBeenCalledWith(
        expect.objectContaining({ name: 'Gold ETF', accountType: 'INVESTMENT', balance: 25000 })
      )
    );
  });

  /**
   * The web page shipped without this check for a while: parseFloat returned NaN, nothing caught
   * it, and "₹NaN" rendered across the totals -- or a negative balance persisted and skewed the
   * allocation chart.
   */
  it('refuses a value that is not a positive number', async () => {
    renderScreen();
    await loaded();

    fireEvent.press(screen.getByText('+ Add'));
    await settle();
    fireEvent.changeText(screen.getByLabelText('Name'), 'Bad');

    for (const bad of ['0', '-500', 'abc', '']) {
      fireEvent.changeText(screen.getByLabelText('Current value'), bad);
      fireEvent.press(screen.getByText('Add Holding'));
      await settle();
    }

    expect(accounts.create).not.toHaveBeenCalled();
  });

  it('saves a net worth snapshot on request', async () => {
    renderScreen();
    await loaded();

    fireEvent.press(screen.getByLabelText("Save today's net worth snapshot"));
    await settle();

    await waitFor(() => expect(networth.saveSnapshot).toHaveBeenCalledTimes(1));
  });

  // A trend needs two points to be a trend; one is a dot.
  it('explains the empty trend rather than drawing a line through one point', async () => {
    networth.current.mockReset().mockResolvedValue({
      totalAssets: 0, totalLiabilities: 0, netWorth: 0,
      history: [{ date: '2026-07-01', netWorth: 450000 }],
    });
    renderScreen();

    expect(await screen.findByText(/Save a snapshot periodically/)).toBeTruthy();
  });

  // useQueries, not Promise.all: one endpoint failing must not blank the other's section.
  it('still lists holdings when the net worth endpoint fails', async () => {
    networth.current.mockReset().mockRejectedValue(new Error('boom'));
    renderScreen();
    await loaded();

    expect(screen.getAllByText('Index fund').length).toBeGreaterThan(0);
    // Net Worth and Liabilities read as unavailable rather than as zero, which would be a lie.
    expect(screen.getAllByText('—')).toHaveLength(2);
  });

  /**
   * The counterpart of the test above, for the query it does NOT cover. `holdings` is [] both when
   * the user genuinely has none and when /accounts failed, so every `length === 0` branch on this
   * screen was answering a question it had no answer to -- and inconsistently: the Holdings card
   * admitted the failure while Allocation and the Investments total did not.
   */
  it('does not state a portfolio of zero when the accounts endpoint fails', async () => {
    accounts.list.mockReset().mockRejectedValue(new Error('boom'));
    renderScreen();

    expect(await screen.findByText('Could not load holdings.')).toBeTruthy();
    expect(screen.getByText('Could not load your allocation.')).toBeTruthy();
    // Crucially NOT "₹0": a fabricated zero is indistinguishable from a real answer.
    expect(screen.queryByText('No investment holdings yet. Add one above, or import a deposit statement.')).toBeNull();
    expect(screen.queryByText('₹0')).toBeNull();
  });

  it('does not tell a user with saved snapshots that they have never saved one', async () => {
    networth.current.mockReset().mockRejectedValue(new Error('boom'));
    renderScreen();
    await loaded();

    expect(screen.getByText('Could not load your net worth history.')).toBeTruthy();
    expect(screen.queryByText(/Save a snapshot periodically/)).toBeNull();
  });

  it('paints a negative net worth as a loss, not a gain', async () => {
    // Ordinary for anyone who imports a credit-card statement before a bank one: the card is a
    // liability, so net worth is legitimately negative and green would invert the signal.
    networth.current.mockReset().mockResolvedValue({
      totalAssets: 0, totalLiabilities: 250000, netWorth: -250000, history: [],
    });
    renderScreen();
    await loaded();

    const value = await screen.findByText('-₹2,50,000');
    expect(value).toHaveStyle({ color: light.danger });
  });
});

/**
 * A first fetch made with no connectivity is PAUSED, not failed -- so every isError guard on this
 * screen skipped it, and every `?? 0` fallback treated it as a settled answer. The screen printed
 * ₹0 as this person's net worth underneath a banner that said "No connection — showing the last
 * data loaded", when nothing had been loaded at all. 'networth' is deliberately excluded from the
 * persistence allowlist, so there is never a cached figure to soften it.
 */
describe('offline, with nothing cached', () => {
  afterEach(() => onlineManager.setOnline(true));

  it('says the figures are unavailable rather than printing zero', async () => {
    onlineManager.setOnline(false);
    // Never called while offline -- the queries pause before reaching the network.
    accounts.list.mockReset().mockResolvedValue([]);
    networth.current.mockReset().mockResolvedValue({
      totalAssets: 0, totalLiabilities: 0, netWorth: 0, history: [],
    });

    renderScreen();
    await settle();

    // Investments, Net Worth and Liabilities all decline to answer.
    await waitFor(() => expect(screen.getAllByText('—')).toHaveLength(3));
    expect(screen.queryByText('₹0')).toBeNull();
  });
});
