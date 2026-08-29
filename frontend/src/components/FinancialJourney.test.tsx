import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { FinancialJourney, journeyDateLabel } from './FinancialJourney';
import { dashboardApi } from '../api/endpoints';
import type { FinancialJourney as FinancialJourneyDto } from '../types';

vi.mock('../api/endpoints', () => ({
  dashboardApi: { journey: vi.fn() },
}));

function journey(overrides: Partial<FinancialJourneyDto> = {}): FinancialJourneyDto {
  return {
    milestones: [
      { type: 'ACCOUNT_CREATED', completed: true, completedAt: '2026-08-01T00:00:00Z' },
      { type: 'FIRST_IMPORT', completed: true, completedAt: '2026-08-02T00:00:00Z' },
      { type: 'FIRST_BUDGET', completed: false, completedAt: null },
      { type: 'FIRST_GOAL', completed: false, completedAt: null },
      { type: 'FIRST_GOAL_ACHIEVED', completed: false, completedAt: null },
    ],
    ...overrides,
  };
}

function renderJourney() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <FinancialJourney />
    </QueryClientProvider>
  );
}

describe('journeyDateLabel', () => {
  beforeEach(() => {
    // Matches Dashboard.test.tsx's own pattern (Subscriptions & Recurring describe block): only
    // Date is faked, so RTL's own setTimeout-based polling still runs for real.
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-08-17T12:00:00Z'));
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('says "today" for a milestone completed within the last 24 hours', () => {
    expect(journeyDateLabel('2026-08-17T06:00:00Z')).toBe('Completed today');
  });

  it('says "yesterday" rather than "1 days ago"', () => {
    expect(journeyDateLabel('2026-08-16T06:00:00Z')).toBe('Completed yesterday');
  });

  it('reports real elapsed days, not a fixed Day-N schedule -- D-25', () => {
    expect(journeyDateLabel('2026-08-10T06:00:00Z')).toBe('Completed 7 days ago');
  });

  it('falls back to a calendar date once the elapsed time is no longer a meaningful "days ago"', () => {
    expect(journeyDateLabel('2026-06-01T06:00:00Z')).toBe('Completed on 1 Jun 2026');
  });
});

describe('FinancialJourney', () => {
  beforeEach(() => {
    vi.mocked(dashboardApi.journey).mockReset();
  });

  it('renders nothing while the query has not resolved yet', () => {
    vi.mocked(dashboardApi.journey).mockReturnValue(new Promise(() => {})); // never resolves
    const { container } = renderJourney();
    expect(container).toBeEmptyDOMElement();
  });

  it('renders nothing if the request fails, rather than an error banner for a nice-to-have card', async () => {
    vi.mocked(dashboardApi.journey).mockRejectedValue(new Error('network'));
    const { container } = renderJourney();
    await new Promise((r) => setTimeout(r, 0));
    expect(container).toBeEmptyDOMElement();
  });

  it('shows all five milestones with human-readable labels', async () => {
    vi.mocked(dashboardApi.journey).mockResolvedValue(journey());
    renderJourney();

    expect(await screen.findByText('Your Financial Journey')).toBeInTheDocument();
    expect(screen.getByText('Account created')).toBeInTheDocument();
    expect(screen.getByText('Imported your first statement')).toBeInTheDocument();
    expect(screen.getByText('Created your first budget')).toBeInTheDocument();
    expect(screen.getByText('Created your first goal')).toBeInTheDocument();
    expect(screen.getByText('Achieved your first goal')).toBeInTheDocument();
  });

  it('shows a running count of how many milestones are complete', async () => {
    vi.mocked(dashboardApi.journey).mockResolvedValue(journey());
    renderJourney();

    expect(await screen.findByText('2 of 5 complete')).toBeInTheDocument();
  });

  it('shows a completed date only for milestones that are actually complete', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-08-17T12:00:00Z'));
    vi.mocked(dashboardApi.journey).mockResolvedValue(journey());
    renderJourney();

    expect(await screen.findByText('Completed 16 days ago')).toBeInTheDocument(); // ACCOUNT_CREATED
    expect(screen.getByText('Completed 15 days ago')).toBeInTheDocument(); // FIRST_IMPORT
    // The three incomplete milestones must not claim a completion date.
    expect(screen.getAllByText(/Completed/)).toHaveLength(2);
    vi.useRealTimers();
  });

  it('reflects every milestone done as a fully completed journey', async () => {
    vi.mocked(dashboardApi.journey).mockResolvedValue(journey({
      milestones: journey().milestones.map((m) => ({ ...m, completed: true, completedAt: m.completedAt ?? '2026-08-10T00:00:00Z' })),
    }));
    renderJourney();

    expect(await screen.findByText('5 of 5 complete')).toBeInTheDocument();
  });

  it('shows the milestone list expanded by default', async () => {
    vi.mocked(dashboardApi.journey).mockResolvedValue(journey());
    renderJourney();

    expect(await screen.findByText('Account created')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Your Financial Journey/ })).toHaveAttribute('aria-expanded', 'true');
  });

  it('collapses the milestone list on click, and expands it again on a second click', async () => {
    const user = userEvent.setup();
    vi.mocked(dashboardApi.journey).mockResolvedValue(journey());
    renderJourney();

    const toggle = await screen.findByRole('button', { name: /Your Financial Journey/ });
    await user.click(toggle);

    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByText('Account created')).not.toBeInTheDocument();
    // The header itself, and the completion count, must stay visible while collapsed.
    expect(screen.getByText('Your Financial Journey')).toBeInTheDocument();
    expect(screen.getByText('2 of 5 complete')).toBeInTheDocument();

    await user.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByText('Account created')).toBeInTheDocument();
  });
});
