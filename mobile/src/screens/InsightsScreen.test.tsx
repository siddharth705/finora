import { render, screen } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { InsightsScreen } from './InsightsScreen';
import { insightsApi, recurringApi } from '../api/endpoints';

jest.mock('../api/endpoints', () => ({
  insightsApi: { get: jest.fn() },
  recurringApi: { list: jest.fn() },
}));

const insights = insightsApi as jest.Mocked<typeof insightsApi>;
const recurring = recurringApi as jest.Mocked<typeof recurringApi>;

function renderScreen() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <InsightsScreen />
    </QueryClientProvider>
  );
}

describe('InsightsScreen', () => {
  beforeEach(() => {
    insights.get.mockReset().mockResolvedValue({
      sentences: ['You spent 18% less on dining this month.'],
      movers: [
        { category: 'Dining', current: 4000, priorAverage: 6000, pctChange: -33 },
        { category: 'Travel', current: 9000, priorAverage: 3000, pctChange: 200 },
        // Filtered out: no prior average means no comparison to make.
        { category: 'New thing', current: 500, priorAverage: 0, pctChange: null },
      ],
    });
    recurring.list.mockReset().mockResolvedValue([
      {
        merchant: 'netflix', label: 'Monthly', averageAmount: 649, occurrences: 6,
        lastDate: '2026-07-04', nextEstimate: '2026-08-04',
      },
    ]);
  });

  it('renders observations, recurring payments and movers', async () => {
    renderScreen();

    expect(await screen.findByText('You spent 18% less on dining this month.')).toBeTruthy();
    expect(screen.getByText('netflix')).toBeTruthy();
    expect(screen.getByText('Dining')).toBeTruthy();
  });

  // Saying plainly that these are statistics, not an AI assistant, is the honest framing -- the
  // same numbers read as something else entirely without it.
  it('does not let the observations pass for AI output', async () => {
    renderScreen();

    expect(await screen.findByText(/not an\s+AI-generated assistant/)).toBeTruthy();
  });

  it('drops movers with nothing to compare against', async () => {
    renderScreen();
    await screen.findByText('Dining');

    expect(screen.queryByText('New thing')).toBeNull();
  });

  // Spending more is the bad direction here -- the inverse of the Dashboard's income KPI.
  it('marks a rise in spending as the adverse direction', async () => {
    renderScreen();

    expect(await screen.findByLabelText(/Travel: ₹9,000 versus a usual ₹3,000, up 200 percent/)).toBeTruthy();
    expect(screen.getByLabelText(/Dining: ₹4,000 versus a usual ₹6,000, down 33 percent/)).toBeTruthy();
  });

  /**
   * useQueries rather than the web page's Promise.all: one rejected promise there loses BOTH
   * sections, though they come from unrelated endpoints.
   */
  it('keeps recurring payments when the insights endpoint fails', async () => {
    insights.get.mockReset().mockRejectedValue(new Error('boom'));
    renderScreen();

    expect(await screen.findByText('netflix')).toBeTruthy();
    expect(screen.getByText(/Couldn't load your insights/)).toBeTruthy();
  });

  it('keeps observations when the recurring endpoint fails', async () => {
    recurring.list.mockReset().mockRejectedValue(new Error('boom'));
    renderScreen();

    expect(await screen.findByText('You spent 18% less on dining this month.')).toBeTruthy();
    expect(screen.getByText(/Couldn't load recurring payments/)).toBeTruthy();
  });

  it('explains why a section is empty rather than showing a blank card', async () => {
    insights.get.mockReset().mockResolvedValue({ sentences: [], movers: [] });
    recurring.list.mockReset().mockResolvedValue([]);
    renderScreen();

    expect(await screen.findByText(/at least 2 charges from the same merchant/)).toBeTruthy();
    expect(screen.getByText(/Not enough history yet/)).toBeTruthy();
  });
});
