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

  it('leaves no timer behind when the user navigates away mid-import', async () => {
    api.timeline.mockResolvedValue(timeline());
    const { unmount } = render(<ImportTimeline jobId="job-1" />);

    await advance(100);
    expect(api.timeline).toHaveBeenCalledTimes(1);

    unmount();
    await advance(10_000);
    expect(api.timeline).toHaveBeenCalledTimes(1);
  });
});
