import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import { ImportProgress, POLL_SCHEDULE_MS } from './ImportProgress';
import { importJobsApi, type ImportJobProgress } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  importJobsApi: { progress: vi.fn(), cancel: vi.fn() },
}));

const api = vi.mocked(importJobsApi);

/**
 * The poll schedule, asserted as a schedule.
 *
 * `queue-overhead-2026-08-08.md` measured the interval — not the queue — as ~98% of the penalty for
 * queueing a small statement. The fix is entirely in *when this component looks*, so the thing worth
 * testing is when it looks, not what it renders while looking.
 *
 * Fake timers throughout: real ones would make this a slow test that asserts "roughly about a
 * second", which is the kind of timing assertion that goes flaky on a loaded CI runner and then gets
 * deleted.
 */

const job = (over: Partial<ImportJobProgress> = {}): ImportJobProgress => ({
  jobId: 'job-1',
  fileName: 'statement.csv',
  status: 'QUEUED',
  rowsTotal: null,
  rowsProcessed: 0,
  createdAt: '2026-08-08T00:00:00Z',
  startedAt: null,
  finishedAt: null,
  importSessionId: null,
  error: null,
  correlationId: 'worker-1',
  ...over,
});

beforeEach(() => {
  vi.useFakeTimers();
  api.progress.mockReset();
  api.cancel.mockReset();
});

afterEach(() => {
  vi.useRealTimers();
});

/**
 * Advances fake time, lets any promise continuations that unblocks run, and flushes the React
 * state updates they produce.
 *
 * Wrapped in `act` because the polling effect sets state from inside an async callback: without it
 * the timer fires and the request resolves, but the render never happens, so a DOM assertion sees
 * the previous frame. Assertions on the mock alone pass either way, which is exactly what makes
 * this worth doing in the helper rather than per test.
 */
async function advance(ms: number) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
}

describe('ImportProgress — poll schedule', () => {
  it('does not poll at zero, because a just-accepted job cannot be finished', () => {
    api.progress.mockResolvedValue(job());
    render(<ImportProgress jobId="job-1" onReady={vi.fn()} onGaveUp={vi.fn()} />);

    // The old code polled here. It always read QUEUED -- the job was accepted milliseconds ago and
    // no worker had touched it -- so its only effect was to push the first useful look out to the
    // full interval.
    expect(api.progress).not.toHaveBeenCalled();
  });

  it('takes its first look at 100ms, when a small statement is already done', async () => {
    api.progress.mockResolvedValue(job());
    render(<ImportProgress jobId="job-1" onReady={vi.fn()} onGaveUp={vi.fn()} />);

    await advance(99);
    expect(api.progress).not.toHaveBeenCalled();

    await advance(1);
    expect(api.progress).toHaveBeenCalledTimes(1);
  });

  /**
   * The schedule itself, pinned to values rather than to its own constant.
   *
   * The loop below iterates POLL_SCHEDULE_MS, so it proves the component FOLLOWS the schedule and
   * says nothing about what the schedule is — it passed unchanged when the constant was reverted to
   * `[1500]`, which is the regression it is meant to catch. These are the numbers the measurement
   * chose; changing them should require changing a test that states them.
   */
  it('starts at 100ms and reaches the 1500ms steady state by the fifth poll', () => {
    expect([...POLL_SCHEDULE_MS]).toEqual([100, 200, 400, 800, 1500]);
  });

  it('backs off along the schedule rather than hammering', async () => {
    api.progress.mockResolvedValue(job());
    render(<ImportProgress jobId="job-1" onReady={vi.fn()} onGaveUp={vi.fn()} />);

    // Each entry in turn: a poll lands at the cumulative sum of the delays before it.
    let elapsed = 0;
    for (const [i, delay] of POLL_SCHEDULE_MS.entries()) {
      await advance(delay);
      elapsed += delay;
      expect(api.progress, `poll ${i + 1} at ${elapsed}ms`).toHaveBeenCalledTimes(i + 1);
    }

    // And then holds at the last value for the life of the job, so a long import costs no more
    // than the 1500ms interval this always had.
    const settledCount = POLL_SCHEDULE_MS.length;
    await advance(1500);
    expect(api.progress).toHaveBeenCalledTimes(settledCount + 1);
    await advance(1500);
    expect(api.progress).toHaveBeenCalledTimes(settledCount + 2);
  });

  /**
   * The measurement's whole point. A 3-row CSV completes server-side at ~40 ms; before this it was
   * not *observed* until 1500 ms. The perceived wait is now the first schedule point after the work
   * finishes.
   */
  it('reports a small statement finished at its first look', async () => {
    const onReady = vi.fn();
    api.progress.mockResolvedValue(
      job({ status: 'COMPLETED', importSessionId: 'session-9', rowsTotal: 3, rowsProcessed: 3 })
    );
    render(<ImportProgress jobId="job-1" onReady={onReady} onGaveUp={vi.fn()} />);

    await advance(100);
    expect(onReady).toHaveBeenCalledWith('session-9');
    expect(api.progress).toHaveBeenCalledTimes(1);
  });

  it('stops polling once the job settles', async () => {
    api.progress.mockResolvedValue(job({ status: 'COMPLETED', importSessionId: 'session-9' }));
    render(<ImportProgress jobId="job-1" onReady={vi.fn()} onGaveUp={vi.fn()} />);

    await advance(100);
    expect(api.progress).toHaveBeenCalledTimes(1);

    // A terminal job never changes again; polling past it is pure waste.
    await advance(10_000);
    expect(api.progress).toHaveBeenCalledTimes(1);
  });

  it('leaves no timer behind when the user navigates away mid-import', async () => {
    api.progress.mockResolvedValue(job());
    const { unmount } = render(<ImportProgress jobId="job-1" onReady={vi.fn()} onGaveUp={vi.fn()} />);

    await advance(100);
    expect(api.progress).toHaveBeenCalledTimes(1);

    unmount();
    await advance(10_000);
    expect(api.progress).toHaveBeenCalledTimes(1);
  });

  /** A blip is not a failed import — the job is on the server either way. Backing off, not giving
   *  up, is the difference between a recoverable hiccup and a stalled screen. */
  it('keeps polling through a transient failure', async () => {
    api.progress
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValue(job({ status: 'COMPLETED', importSessionId: 'session-9' }));
    const onReady = vi.fn();
    render(<ImportProgress jobId="job-1" onReady={onReady} onGaveUp={vi.fn()} />);

    // Asserted directly rather than through waitFor: waitFor polls on REAL time, and with fake
    // timers installed it never gets any, so it hangs until the test times out.
    await advance(100);
    expect(screen.getByText(/still trying/i)).toBeTruthy();
    expect(onReady).not.toHaveBeenCalled();

    // The next scheduled poll succeeds and the screen recovers on its own.
    await advance(200);
    expect(onReady).toHaveBeenCalledWith('session-9');
  });

  /** A slow response must delay the next poll, not stack another on top of it. */
  it('does not overlap polls when a response is slower than the interval', async () => {
    let release: (v: ImportJobProgress) => void = () => {};
    api.progress.mockImplementationOnce(
      () => new Promise<ImportJobProgress>((resolve) => { release = resolve; })
    );
    api.progress.mockResolvedValue(job());
    render(<ImportProgress jobId="job-1" onReady={vi.fn()} onGaveUp={vi.fn()} />);

    await advance(100);
    expect(api.progress).toHaveBeenCalledTimes(1);

    // Well past several schedule points, with the first request still outstanding.
    await advance(5000);
    expect(api.progress).toHaveBeenCalledTimes(1);

    release(job());
    await advance(200);
    expect(api.progress).toHaveBeenCalledTimes(2);
  });
});
