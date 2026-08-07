import { useEffect, useRef, useState } from 'react';
import { AlertTriangle, Ban, Loader2 } from 'lucide-react';
import { importJobsApi, type ImportJobProgress } from '../api/endpoints';
import { detail, isCancellable, isSettled, label, percent } from '../lib/importJob';

/**
 * A queued import, while it happens.
 *
 * <b>Why this exists.</b> The synchronous path holds the request open for the whole parse — measured
 * at 79 seconds for a 5,000-row statement — during which the user has a spinner and no way to tell
 * "slow" from "hung", and closing the tab loses the work. This screen is the other half of the
 * durable queue: the job survives the browser, so the person who started it can watch it, leave it,
 * or stop it.
 *
 * <b>Polling, not a socket.</b> At 1–2s against a flow measured in seconds, one small GET is cheaper
 * to run and far cheaper to operate than the WebSocket or SSE it would otherwise take — and it is
 * the interval the progress endpoint was written for.
 *
 * <b>Stops on settle.</b> A terminal job never changes again, so polling past it is pure waste; the
 * interval is cleared the moment one arrives, and on unmount, so a user who navigates away
 * mid-import leaves no timer behind.
 */
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
    let timer: ReturnType<typeof setInterval> | null = null;

    const stop = () => {
      if (timer !== null) {
        clearInterval(timer);
        timer = null;
      }
    };

    const tick = async () => {
      try {
        const next = await importJobsApi.progress(jobId);
        setJob(next);
        setPollError(null);
        if (!isSettled(next) || settled.current) return;
        // Guarded, because two ticks can be in flight when the job settles and calling back twice
        // would advance the parent's step twice.
        settled.current = true;
        stop();
        if (next.status === 'COMPLETED' && next.importSessionId) onReady(next.importSessionId);
        else onGaveUp(next);
      } catch {
        // A blip mid-poll is not a failed import: the job is on the server either way, and the next
        // tick will pick it up. Said out loud rather than hidden, so a genuinely dead connection
        // does not look like a stalled import.
        setPollError("Lost contact with the server — still trying. Your import is safe.");
      }
    };

    void tick();
    timer = setInterval(() => void tick(), 1500);
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

  return (
    <div className="bg-card rounded-xl2 shadow-card border border-border p-6" data-testid="import-progress">
      <div className="flex items-center gap-3">
        {failed ? (
          <AlertTriangle size={18} className="text-warning flex-shrink-0" />
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
