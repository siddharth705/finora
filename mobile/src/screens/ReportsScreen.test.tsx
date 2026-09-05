import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useNavigation } from '@react-navigation/native';
import { ReportsScreen } from './ReportsScreen';
import { reportsApi, type ReportData } from '../api/endpoints';
import { shareCsv, sharePdf } from '../lib/reportExport';

jest.mock('../api/endpoints', () => ({
  reportsApi: { availableMonths: jest.fn(), forMonth: jest.fn() },
}));

// The export builders are tested directly in reportExport.test.ts; what matters here is that the
// screen reaches them with the report actually on display, and survives a refusal.
jest.mock('../lib/reportExport', () => ({
  shareCsv: jest.fn(),
  sharePdf: jest.fn(),
}));

const api = reportsApi as jest.Mocked<typeof reportsApi>;
const mockShareCsv = shareCsv as jest.MockedFunction<typeof shareCsv>;
const mockSharePdf = sharePdf as jest.MockedFunction<typeof sharePdf>;

const MONTHS = ['2026-05', '2026-06', '2026-07'];

function reportFor(month: string): ReportData {
  return {
    month,
    income: 82000,
    expense: 51500,
    categories: [{ category: 'Groceries', amount: 12000 }],
  };
}

function renderScreen() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <ReportsScreen />
    </QueryClientProvider>
  );
}

async function settle() {
  await act(async () => {});
}

/**
 * Waits for the REPORT, not just the month list. The two load independently, and the month picker
 * appears first -- keying off it presses the export button while it is still correctly disabled.
 */
async function loadedReport() {
  await screen.findByText('Category Breakdown');
}

describe('ReportsScreen', () => {
  beforeEach(() => {
    api.availableMonths.mockReset().mockResolvedValue(MONTHS);
    api.forMonth.mockReset().mockImplementation(async (m: string) => reportFor(m));
    mockShareCsv.mockReset().mockResolvedValue(undefined);
    mockSharePdf.mockReset().mockResolvedValue(undefined);
  });

  // The server returns months ascending; the newest is the one anyone opening this screen wants.
  it('opens on the most recent month', async () => {
    renderScreen();

    await waitFor(() => expect(api.forMonth).toHaveBeenCalledWith('2026-07'));
    expect(await screen.findByLabelText(/Month: Jul 26/)).toBeTruthy();
  });

  it('switches months on selection', async () => {
    renderScreen();
    await screen.findByLabelText(/Month: Jul 26/);

    fireEvent.press(screen.getByLabelText(/Month: Jul 26/));
    await settle();
    fireEvent.press(screen.getByText('2026-05'));
    await settle();

    await waitFor(() => expect(api.forMonth).toHaveBeenCalledWith('2026-05'));
    expect(await screen.findByLabelText(/Month: May 26/)).toBeTruthy();
  });

  // There is nothing to export until the report itself has arrived, so the button stays disabled
  // rather than producing an empty file.
  it('does not offer an export before the report has loaded', async () => {
    // Held pending on purpose: with an instantly-resolving mock the report is already on screen by
    // the time the button can be queried, and the disabled state this asserts never exists.
    api.forMonth.mockReset().mockReturnValue(new Promise(() => {}));
    renderScreen();

    const button = await screen.findByLabelText('Export as CSV');
    expect(button.props.accessibilityState.disabled).toBe(true);
  });

  it('exports the month on screen as CSV', async () => {
    renderScreen();
    await loadedReport();

    fireEvent.press(screen.getByLabelText('Export as CSV'));
    await settle();

    await waitFor(() => expect(mockShareCsv).toHaveBeenCalledTimes(1));
    expect(mockShareCsv.mock.calls[0][0].month).toBe('2026-07');
  });

  it('exports the month on screen as PDF', async () => {
    renderScreen();
    await loadedReport();

    fireEvent.press(screen.getByLabelText('Export as PDF'));
    await settle();

    await waitFor(() => expect(mockSharePdf).toHaveBeenCalledTimes(1));
    expect(mockSharePdf.mock.calls[0][0].month).toBe('2026-07');
  });

  // Sharing is unavailable on some devices and the user can dismiss the sheet -- neither should
  // look like the report itself failed to load.
  it('reports an export failure inline without losing the report', async () => {
    mockShareCsv.mockReset().mockRejectedValue(new Error('Sharing is not available on this device.'));
    renderScreen();
    await loadedReport();

    fireEvent.press(screen.getByLabelText('Export as CSV'));
    await settle();

    expect(await screen.findByText(/Could not export this report as CSV/)).toBeTruthy();
    expect(screen.getByText('Category Breakdown')).toBeTruthy();
  });

  it('points at the import flow when there are no months yet', async () => {
    api.availableMonths.mockReset().mockResolvedValue([]);
    renderScreen();

    expect(await screen.findByText(/import a statement to see reports/i)).toBeTruthy();
    expect(api.forMonth).not.toHaveBeenCalled();
  });

  it('says the report could not be loaded rather than showing a month of zeroes', async () => {
    api.forMonth.mockReset().mockRejectedValue(new Error('boom'));
    renderScreen();

    expect(await screen.findByText(/Couldn't load this month's report/)).toBeTruthy();
  });

  describe('skeleton loading', () => {
    it('shows a skeleton shell instead of a spinner while the month list is loading', () => {
      api.availableMonths.mockReset().mockReturnValue(new Promise(() => {}));
      renderScreen();

      expect(screen.getAllByTestId('shimmer-block', { hidden: true }).length).toBeGreaterThan(0);
    });

    it('skeletons the report body on an uncached month, keeping the month picker usable', async () => {
      renderScreen();
      await loadedReport();

      api.forMonth.mockReset().mockReturnValue(new Promise(() => {}));
      fireEvent.press(screen.getByLabelText(/Month: Jul 26/));
      await settle();
      fireEvent.press(screen.getByText('2026-05'));
      await settle();

      expect(screen.getAllByTestId('shimmer-block', { hidden: true }).length).toBeGreaterThan(0);
      // The month picker -- part of the shell -- must stay mounted and usable while the new
      // month's report is still in flight.
      expect(screen.getByLabelText(/Month: May 26/)).toBeTruthy();
    });
  });
});

/**
 * The backend builds report.expense and report.categories from different transaction sets on
 * purpose: ReportService narrows to excludingInvestmentTransfers(txns) for the income/expense
 * totals, while byCategory keeps the wider list "so an Investments line still shows up in the
 * report's own category table". Dividing a category by report.expense therefore divided some rows
 * by a total they were excluded from -- yielding shares over 100% which ProgressBar then clamped,
 * so two rows of very different size both rendered as full-width bars.
 */
describe('category shares', () => {
  it('keeps shares within 100% when the month contains an investment transfer', async () => {
    api.availableMonths.mockReset().mockResolvedValue(['2026-07']);
    api.forMonth.mockReset().mockResolvedValue({
      month: '2026-07',
      income: 100000,
      // Deliberately LESS than the categories below sum to -- the SIP is excluded from the total
      // but still listed as a category, exactly as the backend produces it.
      expense: 2000,
      categories: [
        { category: 'Investments', amount: 3000 },
        { category: 'Groceries', amount: 2000 },
      ],
    } as never);

    renderScreen();

    // 3000 / 5000 and 2000 / 5000 -- shares of the breakdown they actually belong to, not of a
    // total one of them was excluded from (which gave 150% and 100%).
    expect(
      await screen.findByLabelText(/Investments: ₹3,000, 60 percent of this month's spending/)
    ).toBeTruthy();
    expect(
      screen.getByLabelText(/Groceries: ₹2,000, 40 percent of this month's spending/)
    ).toBeTruthy();
  });
});

describe('drill-through into the ledger (Track C/C4)', () => {
  beforeEach(() => {
    api.availableMonths.mockReset().mockResolvedValue(MONTHS);
    api.forMonth.mockReset().mockImplementation(async (m: string) => reportFor(m));
  });

  it('opens Transactions filtered to this category and the whole month currently on screen', async () => {
    renderScreen();
    await loadedReport();
    const { navigate } = useNavigation<never>() as unknown as { navigate: jest.Mock };
    navigate.mockClear();

    fireEvent.press(screen.getByLabelText(/Groceries: ₹12,000/));

    expect(navigate).toHaveBeenCalledWith('Transactions', {
      filters: expect.objectContaining({
        categoryName: 'Groceries', dateFrom: '2026-07-01', dateTo: '2026-07-31', label: 'Groceries · Jul 26',
      }),
    });
  });
});
