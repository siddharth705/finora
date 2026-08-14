import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { ImportTimeline } from './ImportTimeline';
import { POLL_SCHEDULE_MS } from './ImportProgress';
import { importJobsApi, type ImportJobTimeline as Timeline } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  importJobsApi: { timeline: vi.fn() },
}));

const api = vi.mocked(importJobsApi);

const timeline = (over: Partial<Timeline> = {}): Timeline => ({
  jobId: 'job-1',
  status: 'ANALYZING',
  failureCode: null,
  stages: [
    { stage: 'PARSING', attempt: 1, outcome: 'COMPLETED', startedAt: '2026-08-12T10:00:00Z', endedAt: '2026-08-12T10:00:01Z', durationMs: 1000 },
    { stage: 'ANALYZING', attempt: 1, outcome: 'RUNNING', startedAt: '2026-08-12T10:00:01Z', endedAt: null, durationMs: null },
  ],
  ...over,
});

beforeEach(() => {
  vi.useFakeTimers();
  api.timeline.mockReset();
});

afterEach(() => {
  vi.useRealTimers();
});

/** Same discipline as ImportProgress.test.tsx: `findBy*`/`waitFor` poll on REAL time, and with
 *  fake timers installed they never get any, so they hang until the test times out. Every
 *  assertion here uses `getBy*`/`queryBy*` directly after `advance()` has already flushed the
 *  state update. */
async function advance(ms: number) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
}

describe('ImportTimeline', () => {
  it('renders nothing before the first poll lands', () => {
    api.timeline.mockResolvedValue(timeline());
    render(<ImportTimeline jobId="job-1" />);

    expect(screen.queryByTestId('import-timeline')).not.toBeInTheDocument();
  });

  it('renders nothing when the job has no stages yet', async () => {
    api.timeline.mockResolvedValue(timeline({ stages: [] }));
    render(<ImportTimeline jobId="job-1" />);

    await advance(100);

    expect(screen.queryByTestId('import-timeline')).not.toBeInTheDocument();
  });

  /**
   * The stranding bug a code review caught: ImportStageRecorder deliberately tolerates its own
   * write failing without breaking the import ("a measurement gap, not an outage"), so a FAILED
   * job can genuinely reach the client with an empty stage list. ImportProgress no longer offers
   * its own way back to the dropzone for FAILED (Import.tsx delegates that to this component's
   * onDismiss) -- so before this test's fix, "no stages" and "no way back" were the same branch,
   * and a customer with a stage-recording gap would be stranded on the failed screen.
   */
  it('still offers the curated reason and a way back for a failed job with no recorded stages', async () => {
    api.timeline.mockResolvedValue(timeline({ status: 'FAILED', failureCode: null, stages: [] }));
    const onDismiss = vi.fn();
    render(<ImportTimeline jobId="job-1" onDismiss={onDismiss} />);

    await advance(100);

    expect(screen.getByTestId('import-timeline')).toBeInTheDocument();
    expect(screen.getByTestId('import-timeline-failure-reason')).toBeInTheDocument();
    act(() => {
      fireEvent.click(screen.getByRole('button', { name: 'Try a different file' }));
    });
    expect(onDismiss).toHaveBeenCalledTimes(1);
  });

  it('lists every stage once the first poll lands', async () => {
    api.timeline.mockResolvedValue(timeline());
    render(<ImportTimeline jobId="job-1" />);

    await advance(100);

    expect(screen.getByTestId('import-timeline')).toBeInTheDocument();
    expect(screen.getByText('Reading your statement')).toBeInTheDocument();
    expect(screen.getByText('Finding the transactions')).toBeInTheDocument();
  });

  it('labels a stage past its first attempt', async () => {
    api.timeline.mockResolvedValue(timeline({
      stages: [
        { stage: 'PARSING', attempt: 1, outcome: 'FAILED', startedAt: '2026-08-12T10:00:00Z', endedAt: '2026-08-12T10:00:01Z', durationMs: 1000 },
        { stage: 'PARSING', attempt: 2, outcome: 'COMPLETED', startedAt: '2026-08-12T10:01:00Z', endedAt: '2026-08-12T10:01:01Z', durationMs: 1000 },
      ],
    }));
    render(<ImportTimeline jobId="job-1" />);

    await advance(100);

    expect(screen.getByText('(attempt 2)')).toBeInTheDocument();
  });

  /**
   * Bug fix: OUTCOME_ICON/OUTCOME_COLOR used to be plain Records with no fallback, unlike the
   * sibling `stageLabel` lookup, which already guards the identical class of lookup with `??
   * 'Working'`. An outcome value the frontend's hand-written union hasn't been updated for (drift
   * from the backend's ImportJobStage.Outcome enum, which isn't compiler-checked against this
   * union) would resolve `<Icon />` to `undefined` and crash the whole timeline instead of
   * degrading gracefully.
   */
  it('renders a fallback rather than crashing for a stage outcome the frontend type has not seen', async () => {
    api.timeline.mockResolvedValue(timeline({
      stages: [
        // Cast past the type system -- exactly the drift scenario this test guards against;
        // TypeScript alone can't catch a backend enum value the frontend union wasn't updated for.
        { stage: 'PARSING', attempt: 1, outcome: 'UNKNOWN_FUTURE_OUTCOME' as Timeline['stages'][number]['outcome'], startedAt: '2026-08-12T10:00:00Z', endedAt: null, durationMs: null },
      ],
    }));

    expect(() => render(<ImportTimeline jobId="job-1" />)).not.toThrow();
    await advance(100);

    expect(screen.getByTestId('import-timeline')).toBeInTheDocument();
  });

  it('shows the curated reason for a known failure code, not the raw code', async () => {
    api.timeline.mockResolvedValue(timeline({
      status: 'FAILED',
      failureCode: 'IMPORT_001', // NO_HEADER_DETECTED
      stages: [
        { stage: 'PARSING', attempt: 1, outcome: 'FAILED', startedAt: '2026-08-12T10:00:00Z', endedAt: '2026-08-12T10:00:01Z', durationMs: 1000 },
      ],
    }));
    render(<ImportTimeline jobId="job-1" />);

    await advance(100);

    const reason = screen.getByTestId('import-timeline-failure-reason');
    expect(reason.textContent).toContain("couldn't find a transaction table");
    expect(reason.textContent).not.toContain('IMPORT_001');
  });

  it('falls back to a generic message for a failure with no curated code', async () => {
    api.timeline.mockResolvedValue(timeline({
      status: 'FAILED',
      failureCode: null,
      stages: [
        { stage: 'PARSING', attempt: 1, outcome: 'FAILED', startedAt: '2026-08-12T10:00:00Z', endedAt: '2026-08-12T10:00:01Z', durationMs: 1000 },
      ],
    }));
    render(<ImportTimeline jobId="job-1" />);

    await advance(100);

    expect(screen.getByTestId('import-timeline-failure-reason')).toHaveTextContent(
      "Finora couldn't complete this import"
    );
  });

  it('does not show a failure reason or dismiss action for a completed import', async () => {
    api.timeline.mockResolvedValue(timeline({ status: 'COMPLETED' }));
    render(<ImportTimeline jobId="job-1" />);

    await advance(100);

    expect(screen.getByTestId('import-timeline')).toBeInTheDocument();
    expect(screen.queryByTestId('import-timeline-failure-reason')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Try a different file' })).not.toBeInTheDocument();
  });

  it('calls onDismiss when the user asks to try a different file', async () => {
    api.timeline.mockResolvedValue(timeline({
      status: 'FAILED',
      failureCode: 'IMPORT_011', // CORRUPT_PDF
      stages: [
        { stage: 'PARSING', attempt: 1, outcome: 'FAILED', startedAt: '2026-08-12T10:00:00Z', endedAt: '2026-08-12T10:00:01Z', durationMs: 1000 },
      ],
    }));
    const onDismiss = vi.fn();
    render(<ImportTimeline jobId="job-1" onDismiss={onDismiss} />);

    await advance(100);
    // fireEvent rather than userEvent: userEvent's own internal delay-between-events machinery
    // fights the fake timers already installed for the polling loop above.
    act(() => {
      fireEvent.click(screen.getByRole('button', { name: 'Try a different file' }));
    });

    expect(onDismiss).toHaveBeenCalledTimes(1);
  });

  it('stops polling once the job settles', async () => {
    api.timeline.mockResolvedValue(timeline({ status: 'COMPLETED' }));
    render(<ImportTimeline jobId="job-1" />);

    await advance(100);
    expect(api.timeline).toHaveBeenCalledTimes(1);

    await advance(10_000);
    expect(api.timeline).toHaveBeenCalledTimes(1);
  });

  it('follows the same poll schedule as ImportProgress, so the two requests land together', async () => {
    api.timeline.mockResolvedValue(timeline());
    render(<ImportTimeline jobId="job-1" />);

    let elapsed = 0;
    for (const [i, delay] of POLL_SCHEDULE_MS.entries()) {
      await advance(delay);
      elapsed += delay;
      expect(api.timeline, `poll ${i + 1} at ${elapsed}ms`).toHaveBeenCalledTimes(i + 1);
    }
  });

  it('keeps polling through a transient failure', async () => {
    api.timeline.mockRejectedValueOnce(new Error('network')).mockResolvedValue(timeline());
    render(<ImportTimeline jobId="job-1" />);

    await advance(100);
    await advance(200);

    expect(api.timeline).toHaveBeenCalledTimes(2);
  });

  /**
   * Bug fix: the one-shot mode's "show pollError instead of rendering nothing" fix used to apply
   * unconditionally, so it leaked into this default polling mode too -- a first-poll blip during
   * a live upload started showing a duplicate "Lost contact with the server" card right next to
   * ImportProgress's own near-identical one. Polling mode keeps its original silent behavior: a
   * blip is not a failed import, and the next scheduled poll (asserted above) recovers on its own.
   */
  it('renders nothing (not an error card) while a first-poll blip is still retrying', async () => {
    api.timeline.mockRejectedValueOnce(new Error('network')).mockResolvedValue(timeline());
    render(<ImportTimeline jobId="job-1" />);

    await advance(100);

    expect(screen.queryByTestId('import-timeline')).not.toBeInTheDocument();
  });

  it('leaves no timer behind when the user navigates away mid-import', async () => {
    api.timeline.mockResolvedValue(timeline());
    const { unmount } = render(<ImportTimeline jobId="job-1" />);

    await advance(100);
    expect(api.timeline).toHaveBeenCalledTimes(1);

    unmount();
    await advance(10_000);
    expect(api.timeline).toHaveBeenCalledTimes(1);
  });

  /**
   * The import detail page (Premium Import Reliability v1, §3.2) passes autoRefresh={false}: one
   * fetch on mount, a manual Refresh button rather than a background poll -- even for a job that is
   * still active, unlike every other caller of this component.
   */
  describe('autoRefresh={false} -- the detail page one-shot mode', () => {
    it('fetches immediately rather than waiting for the first scheduled delay', async () => {
      api.timeline.mockResolvedValue(timeline());
      render(<ImportTimeline jobId="job-1" autoRefresh={false} />);

      // advance(0), not a bare act() -- the fetch is scheduled via a zero-delay setTimeout (so
      // StrictMode's double-invoke can still cancel the discarded instance's request the same way
      // the polling path always has), not called inline. ImportProgress's own schedule deliberately
      // delays the first look by a REAL amount (see its doc comment on why); this has no such wait
      // to respect, so the delay here is 0, not absent.
      await advance(0);
      expect(api.timeline).toHaveBeenCalledTimes(1);
    });

    it('does not keep polling even when the job is still active', async () => {
      api.timeline.mockResolvedValue(timeline({ status: 'ANALYZING' }));
      render(<ImportTimeline jobId="job-1" autoRefresh={false} />);

      await advance(100);
      expect(api.timeline).toHaveBeenCalledTimes(1);

      await advance(10_000);
      expect(api.timeline).toHaveBeenCalledTimes(1);
    });

    it('fetches exactly once more each time refreshToken changes', async () => {
      api.timeline.mockResolvedValue(timeline({ status: 'ANALYZING' }));
      const { rerender } = render(<ImportTimeline jobId="job-1" autoRefresh={false} refreshToken={0} />);
      await advance(0);
      expect(api.timeline).toHaveBeenCalledTimes(1);

      rerender(<ImportTimeline jobId="job-1" autoRefresh={false} refreshToken={1} />);
      await advance(0);
      expect(api.timeline).toHaveBeenCalledTimes(2);

      await advance(10_000);
      expect(api.timeline).toHaveBeenCalledTimes(2);
    });

    /**
     * Bug fix: a failed first fetch used to leave `timeline` null forever (nothing retries in this
     * mode) and the render guard was `if (!timeline) return null`, which discarded pollError right
     * along with it -- the whole section silently didn't exist, with no error text and no hint that
     * clicking the page's Refresh button would fix it.
     */
    it('shows the error instead of rendering nothing when the one-shot fetch itself fails', async () => {
      api.timeline.mockRejectedValue(new Error('network'));
      render(<ImportTimeline jobId="job-1" autoRefresh={false} />);

      await advance(0);

      expect(screen.getByTestId('import-timeline')).toBeInTheDocument();
      expect(screen.getByText(/couldn't load the timeline/i)).toBeInTheDocument();
    });

    /** The polling-mode text ("...still trying") would be a lie here: schedule()'s own !autoRefresh
     *  guard means nothing actually retries on its own in this mode. */
    it('does not claim it is still trying, since nothing retries on its own in this mode', async () => {
      api.timeline.mockRejectedValue(new Error('network'));
      render(<ImportTimeline jobId="job-1" autoRefresh={false} />);

      await advance(0);

      expect(screen.queryByText(/still trying/i)).not.toBeInTheDocument();
    });

    /**
     * Bug fix: a REFRESH that fails after an earlier fetch already succeeded used to show the
     * exact same "Couldn't load the timeline" text a first-ever failure shows -- misleading, since
     * by definition a fetch already worked (the stages rendered from it are still on screen right
     * above this line) and Refresh is what failed, not the initial load.
     */
    it('says the refresh failed, not that the timeline never loaded, once something already rendered', async () => {
      api.timeline.mockResolvedValueOnce(timeline({ status: 'ANALYZING' })).mockRejectedValueOnce(new Error('network'));
      const { rerender } = render(<ImportTimeline jobId="job-1" autoRefresh={false} refreshToken={0} />);
      await advance(0);
      expect(screen.getByTestId('import-timeline')).toBeInTheDocument();

      rerender(<ImportTimeline jobId="job-1" autoRefresh={false} refreshToken={1} />);
      await advance(0);

      expect(screen.getByText(/couldn't refresh -- showing the last known status/i)).toBeInTheDocument();
      expect(screen.queryByText(/couldn't load the timeline/i)).not.toBeInTheDocument();
    });

    /**
     * Bug fix: the empty-stage-list early return (`if (timeline.stages.length === 0 ...) return
     * null`) ran even when a refresh had just failed on top of an earlier empty-but-successful
     * fetch, silently discarding pollError the same way the very-first-fetch case used to.
     */
    it('still shows a refresh failure even when the last successful fetch had no stages recorded yet', async () => {
      api.timeline.mockResolvedValueOnce(timeline({ status: 'QUEUED', stages: [] })).mockRejectedValueOnce(new Error('network'));
      const { rerender } = render(<ImportTimeline jobId="job-1" autoRefresh={false} refreshToken={0} />);
      await advance(0);
      expect(screen.queryByTestId('import-timeline')).not.toBeInTheDocument();

      rerender(<ImportTimeline jobId="job-1" autoRefresh={false} refreshToken={1} />);
      await advance(0);

      expect(screen.getByTestId('import-timeline')).toBeInTheDocument();
      expect(screen.getByText(/couldn't refresh -- showing the last known status/i)).toBeInTheDocument();
    });
  });
});
