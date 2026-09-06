import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import Insights from './Insights';
import { insightsApi, recurringApi, onboardingApi, type InsightsData, type RecurringItem } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  insightsApi: { get: vi.fn() },
  recurringApi: { list: vi.fn() },
  // Getting-started checklist dwell timer (D-onboarding) -- default to "no VIEW_INSIGHTS item in
  // the response" so it never fires in tests that don't care about it; the dwell-timer's own
  // tests override this.
  onboardingApi: {
    getChecklist: vi.fn().mockResolvedValue({ items: [], completedCount: 0, totalCount: 6 }),
    completeChecklistItem: vi.fn().mockResolvedValue(undefined),
  },
}));

function insights(overrides: Partial<InsightsData> = {}): InsightsData {
  return {
    sentences: ['You spent more on Food this month than usual.'],
    movers: [{ category: 'Food', current: 8000, priorAverage: 5000, pctChange: 60 }],
    ...overrides,
  };
}

function recurringItem(overrides: Partial<RecurringItem> = {}): RecurringItem {
  return {
    merchant: 'netflix',
    label: 'Monthly',
    averageAmount: 649,
    occurrences: 4,
    lastDate: '2026-08-01',
    nextEstimate: '2026-09-01',
    ...overrides,
  };
}

/** A promise that never settles -- "this endpoint is still in flight". */
function pending<T>(): Promise<T> {
  return new Promise<T>(() => {});
}

describe('Insights — section-scoped loading', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  /**
   * The page used to await Promise.all([insightsApi.get(), recurringApi.list()]) behind ONE
   * `loading` flag, so the whole page waited on the slower of two endpoints that have no dependency
   * on each other. §1's rule: section-scoped loading whenever sections are independently sourced.
   */
  it('renders the Recurring card as soon as /recurring resolves, without waiting on /insights', async () => {
    vi.mocked(insightsApi.get).mockReturnValue(pending<InsightsData>());
    vi.mocked(recurringApi.list).mockResolvedValue([recurringItem()]);

    render(<Insights />);

    expect(await screen.findByText(/netflix/i)).toBeInTheDocument();
    // ...while the other two cards are still, correctly, loading.
    expect(screen.getByText("Loading this month's observations")).toBeInTheDocument();
    expect(screen.getByText('Loading category movers')).toBeInTheDocument();
  });

  it('renders Observations and Movers as soon as /insights resolves, without waiting on /recurring', async () => {
    vi.mocked(insightsApi.get).mockResolvedValue(insights());
    vi.mocked(recurringApi.list).mockReturnValue(pending<RecurringItem[]>());

    render(<Insights />);

    expect(await screen.findByText(/You spent more on Food/)).toBeInTheDocument();
    expect(screen.getByText('Loading recurring payments')).toBeInTheDocument();
  });

  /**
   * The sharper half of the same bug: `error` was shared, so ONE failing endpoint replaced the whole
   * page with a single line of text -- including the sections whose own fetch had succeeded.
   */
  it('keeps Observations and Movers when /recurring is the endpoint that failed', async () => {
    vi.mocked(insightsApi.get).mockResolvedValue(insights());
    vi.mocked(recurringApi.list).mockRejectedValue(new Error('boom'));

    render(<Insights />);

    expect(await screen.findByText(/Couldn't load your recurring payments/)).toBeInTheDocument();
    expect(screen.getByText(/You spent more on Food/)).toBeInTheDocument();
    expect(screen.getByText('Food')).toBeInTheDocument();
  });

  it('keeps the Recurring card when /insights is the endpoint that failed', async () => {
    vi.mocked(insightsApi.get).mockRejectedValue(new Error('boom'));
    vi.mocked(recurringApi.list).mockResolvedValue([recurringItem()]);

    render(<Insights />);

    expect(await screen.findByText(/netflix/i)).toBeInTheDocument();
    expect(screen.getAllByText(/Couldn't load your insights/)).toHaveLength(2);
  });

  /**
   * The accessibility contract that makes this swap an improvement rather than a regression: the
   * page previously rendered readable "Loading…" text, so replacing it with aria-hidden shapes and
   * no announcing region would be strictly worse. The Region must be present IMMEDIATELY, before
   * useDelayedLoading's anti-flash window has elapsed and any shape has rendered.
   */
  it('announces each loading section immediately, before any skeleton shape appears', () => {
    vi.mocked(insightsApi.get).mockReturnValue(pending<InsightsData>());
    vi.mocked(recurringApi.list).mockReturnValue(pending<RecurringItem[]>());

    render(<Insights />);

    const regions = screen.getAllByRole('status');
    expect(regions).toHaveLength(3);
    regions.forEach((r) => expect(r).toHaveAttribute('aria-busy', 'true'));
    expect(screen.getByText("Loading this month's observations")).toBeInTheDocument();
    expect(screen.getByText('Loading recurring payments')).toBeInTheDocument();
    expect(screen.getByText('Loading category movers')).toBeInTheDocument();
  });
});

describe('Insights — getting-started checklist dwell timer', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(insightsApi.get).mockResolvedValue(insights());
    vi.mocked(recurringApi.list).mockResolvedValue([]);
  });

  it('marks VIEW_INSIGHTS complete after a 1.5s dwell', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.mocked(onboardingApi.getChecklist).mockResolvedValue({
      items: [{ key: 'VIEW_INSIGHTS', completed: false }], completedCount: 0, totalCount: 6,
    });
    const completeSpy = vi.mocked(onboardingApi.completeChecklistItem).mockResolvedValue(undefined as any);

    render(<Insights />);

    // Flushes the getChecklist().then(setChecklist) microtask (and the re-render/effect it
    // triggers) before advancing to the dwell timer itself -- vi.waitFor's own polling is timer-
    // based and deadlocks against fake timers, so this uses advanceTimersByTimeAsync(0) instead.
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(1500);

    expect(completeSpy).toHaveBeenCalledWith('VIEW_INSIGHTS');
    vi.useRealTimers();
  });

  it('does not fire if the item is already complete', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.mocked(onboardingApi.getChecklist).mockResolvedValue({
      items: [{ key: 'VIEW_INSIGHTS', completed: true }], completedCount: 1, totalCount: 6,
    });
    const completeSpy = vi.mocked(onboardingApi.completeChecklistItem).mockResolvedValue(undefined as any);

    render(<Insights />);

    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(1500);

    expect(completeSpy).not.toHaveBeenCalled();
    vi.useRealTimers();
  });
});
