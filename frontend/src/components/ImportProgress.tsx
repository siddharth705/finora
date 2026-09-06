import { useEffect, useRef, useState } from 'react';
import { AlertTriangle, Ban, Clock, Loader2 } from 'lucide-react';
import { importJobsApi, type ImportJobProgress } from '../api/endpoints';
import { detail, isCancellable, isHeld, isSettled, label, percent } from '../lib/importJob';

/**
 * A queued import, while it happens.
 *
 * <b>Why this exists.</b> The synchronous path holds the request open for the whole parse — measured
 * at 79 seconds for a 5,000-row statement — during which the user has a spinner and no way to tell
 * "slow" from "hung", and closing the tab loses the work. This screen is the other half of the
 * durable queue: the job survives the browser, so the person who started it can watch it, leave it,
 * or stop it.
 *
 * <b>Polling, not a socket.</b> One small GET against a flow measured in seconds is cheaper to run
 * and far cheaper to operate than the WebSocket or SSE it would otherwise take.
 *
 * <b>The schedule is the product decision, not an implementation detail.</b> This used to poll once
 * immediately and then every 1500 ms, and the measurement in
 * `docs/engineering/performance/queue-overhead-2026-08-08.md` found that the interval — not the
 * queue — was ~98% of the penalty for queueing a small statement. The server-side difference for a
 * 3-row CSV was 19 ms synchronous against 40 ms queued, which nobody can perceive; the wait was
 * entirely the client not looking.
 *
 * The immediate poll did not help, and could not: it fires when the job has just been accepted and
 * no worker has touched it, so it always reads QUEUED. Its only effect was to make the *second*
 * poll — at 1500 ms — the first one that could observe a finished job.
 *
 * So the first real look is at 100 ms, and the interval grows from there to the 1500 ms this was
 * always written for. A small statement is finished well inside the first step; a large one backs
 * off to the same cost as before within five polls. {@link POLL_SCHEDULE_MS} is exported because
 * the test asserts the schedule, and because a number chosen from a measurement should be findable
 * from the measurement.
 *
 * <b>Stops on settle.</b> A terminal job never changes again, so polling past it is pure waste; the
 * timer is cleared the moment one arrives, and on unmount, so a user who navigates away mid-import
 * leaves no timer behind.
 */

/**
 * Delay before each poll, in order; the last value repeats for the life of the job.
 *
 * Starts at 100 ms because the measured completion for a small statement is ~40 ms — the first look
 * should land after the work is plausibly done, not while it is provably not. Reaches 1500 ms at the
 * fifth poll, so a long import costs no more requests than it did before: five extra GETs in the
 * first 3 seconds, and identical behaviour thereafter.
 */
export const POLL_SCHEDULE_MS = [100, 200, 400, 800, 1500] as const;
export function ImportProgress({
  jobId,
  onReady,
  onGaveUp,
}: {
  jobId: string;
  /** The job finished with rows to review. Carries the session the review screen loads. */
  onReady: (sessionId: string) => void;
  /** The job ended with nothing to review — failed, or cancelled by this user. */
  onGaveUp: (job: ImportJobProgress) => void;
}) {
  const [job, setJob] = useState<ImportJobProgress | null>(null);
  const [cancelling, setCancelling] = useState(false);
  const [pollError, setPollError] = useState<string | null>(null);
  // Held in a ref, not state: the callbacks are called from inside the polling effect and must not
  // be part of its dependency list, or a parent that re-renders would tear down and restart the
  // interval on every render.
  const settled = useRef(false);

  useEffect(() => {
    settled.current = false;
    let timer: ReturnType<typeof setTimeout> | null = null;
    let poll = 0;
    let stopped = false;

    const stop = () => {
      stopped = true;
      if (timer !== null) {
        clearTimeout(timer);
        timer = null;
      }
    };

    // A chained timeout rather than setInterval, because the delay changes between polls and
    // because it cannot overlap: the next one is scheduled after the previous request settles, so a
    // slow response delays the next poll instead of stacking another on top of it.
    const schedule = () => {
      if (stopped) return;
      const delay = POLL_SCHEDULE_MS[Math.min(poll, POLL_SCHEDULE_MS.length - 1)];
      poll += 1;
      timer = setTimeout(() => void tick(), delay);
    };

    const tick = async () => {
      try {
        const next = await importJobsApi.progress(jobId);
        if (stopped) return;
        setJob(next);
        setPollError(null);
        if (!isSettled(next) || settled.current) {
          schedule();
          return;
        }
        // Guarded, because two ticks can be in flight when the job settles and calling back twice
        // would advance the parent's step twice.
        settled.current = true;
        stop();
        if (next.status === 'COMPLETED' && next.importSessionId) onReady(next.importSessionId);
        else onGaveUp(next);
      } catch {
        // A blip mid-poll is not a failed import: the job is on the server either way, and the next
        // tick will pick it up. Said out loud rather than hidden, so a genuinely dead connection
        // does not look like a stalled import. Keeps polling -- backing off, not giving up.
        if (stopped) return;
        setPollError("Lost contact with the server — still trying. Your import is safe.");
        schedule();
      }
    };

    schedule();
    return stop;
    // onReady/onGaveUp are deliberately not dependencies -- see the ref above.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [jobId]);

  async function cancel() {
    setCancelling(true);
    try {
      const cancelled = await importJobsApi.cancel(jobId);
      setJob(cancelled);
      if (isSettled(cancelled) && !settled.current) {
        settled.current = true;
        onGaveUp(cancelled);
      }
    } catch (e: any) {
      // 409 is the interesting one: the import finished or moved past the point of no return while
      // the user was reaching for the button. The server's message says which, and it is a real
      // answer rather than a generic failure -- so it is shown as-is and the next poll corrects the
      // rest of the screen.
      setPollError(e.response?.data?.message ?? 'Could not cancel this import.');
    } finally {
      setCancelling(false);
    }
  }

  const pct = job ? percent(job) : null;
  const failed = job?.status === 'FAILED';
  // Settled, so the spinner stops and the progress bar and Cancel disappear -- but not a
  // failure and not a cancellation, so neither of those icons is honest. Waiting on us.
  //
  // Both holds, because the fallback below is the cancelled icon: a settled job that is not FAILED
  // and not recognised here renders a Ban glyph next to "Running additional checks", which tells
  // the user their import was cancelled and that it is still being worked on, at the same time.
  const held = job ? isHeld(job) : false;

  return (
    <div className="bg-card rounded-xl2 shadow-card border border-border p-6" data-testid="import-progress">
      <div className="flex items-center gap-3">
        {failed ? (
          <AlertTriangle size={18} className="text-warning flex-shrink-0" />
        ) : held ? (
          <Clock size={18} className="text-primary flex-shrink-0" />
        ) : job && isSettled(job) ? (
          <Ban size={18} className="text-muted flex-shrink-0" />
        ) : (
          <Loader2 size={18} className="text-primary flex-shrink-0 animate-spin" />
        )}
        <div className="min-w-0">
          <p className="text-sm font-semibold text-ink">{job ? label(job) : 'Uploading'}</p>
          {job && detail(job) && <p className="text-xs text-muted mt-0.5">{detail(job)}</p>}
        </div>

        {job && isCancellable(job) && (
          <button
            type="button"
            onClick={() => void cancel()}
            disabled={cancelling}
            className="ml-auto text-xs font-medium text-muted hover:text-ink underline disabled:opacity-50"
          >
            {cancelling ? 'Cancelling…' : 'Cancel'}
          </button>
        )}
      </div>

      {/* Indeterminate until the statement has been counted. A bar pinned at 0% reads as "nothing is
          happening", when the truth is that the total is not known yet. */}
      {!failed && job && !isSettled(job) && (
        <div className="mt-4 h-1.5 w-full rounded-full bg-border overflow-hidden">
          {pct === null ? (
            <div className="h-full w-1/3 rounded-full bg-primary animate-pulse" />
          ) : (
            <div
              className="h-full rounded-full bg-primary transition-all duration-500"
              style={{ width: `${pct}%` }}
              role="progressbar"
              aria-valuenow={pct}
              aria-valuemin={0}
              aria-valuemax={100}
            />
          )}
        </div>
      )}

      {/* The promise the durable queue actually makes, stated where it is useful rather than in a
          release note: this is why the queue was worth building. */}
      {job && !isSettled(job) && (
        <p className="text-xs text-muted mt-3">
          You can close this page — the import keeps going, and it'll be waiting for you.
        </p>
      )}

      {pollError && <p className="text-xs text-warning mt-3">{pollError}</p>}
    </div>
  );
}
