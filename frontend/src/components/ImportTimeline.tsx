import { useEffect, useRef, useState } from 'react';
import { CheckCircle2, AlertTriangle, Loader2, MinusCircle } from 'lucide-react';
import { importJobsApi, type ImportJobTimeline as Timeline, type ImportTimelineStage } from '../api/endpoints';
import { isSettled, stageLabel } from '../lib/importJob';
import { importFailureMessage } from '../api/importFailureMessages';
import { formatTime } from '../utils/date';
import { POLL_SCHEDULE_MS } from './ImportProgress';

/**
 * The stage-by-stage history behind {@link import('./ImportProgress').ImportProgress}'s single
 * current status -- Premium Import Reliability v1, §3.1. Where {@code ImportProgress} answers
 * "what's happening right now", this answers "what actually happened", including a curated reason
 * once the job has FAILED -- turning "Failed" into "10:04 Validating statement -- Failed. This PDF
 * could not be read" instead of leaving the person to guess or contact support.
 *
 * <b>Self-contained polling, same schedule as {@code ImportProgress}.</b> Rather than lifting the
 * parent's already-polled job state, this fetches its own `/timeline` on the identical backoff
 * schedule and stops on the identical terminal-state rule -- the two calls are independent (one
 * DTO cannot answer both "what's the single current status" and "what's the full stage history"
 * without becoming both at once), and reusing the schedule means the two requests land together
 * rather than drifting out of step with each other.
 */
export function ImportTimeline({
  jobId,
  onDismiss,
  autoRefresh = true,
  refreshToken = 0,
}: {
  jobId: string;
  /** Offered once the job has FAILED, so reading the curated reason doesn't strand the user --
   *  the dropzone this replaced is only reachable again through this, not through polling settling
   *  on its own the way a completed/cancelled job's screen already resets automatically. */
  onDismiss?: () => void;
  /** Default true, matching every existing caller (a live upload in progress). The import detail
   *  page (Premium Import Reliability v1, §3.2) passes false: that page's own design is a single
   *  fetch on load plus a manual Refresh button, not a background poll -- most visits land on an
   *  already-terminal job, where a poll would just be one fetch anyway, but for the rare
   *  still-processing case the page must not keep polling on its own. */
  autoRefresh?: boolean;
  /** Bumped by the detail page's Refresh button to trigger exactly one more fetch when
   *  autoRefresh is false. Unused (and pointless to change) when autoRefresh is true, since the
   *  schedule already keeps fetching on its own. */
  refreshToken?: number;
}) {
  const [timeline, setTimeline] = useState<Timeline | null>(null);
  const [pollError, setPollError] = useState<string | null>(null);
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

    const schedule = () => {
      if (stopped || !autoRefresh) return;
      const delay = POLL_SCHEDULE_MS[Math.min(poll, POLL_SCHEDULE_MS.length - 1)];
      poll += 1;
      timer = setTimeout(() => void tick(), delay);
    };

    const tick = async () => {
      try {
        const next = await importJobsApi.timeline(jobId);
        if (stopped) return;
        setTimeline(next);
        setPollError(null);
        if (isSettled(next) && !settled.current) {
          settled.current = true;
          stop();
          return;
        }
        schedule();
      } catch {
        if (stopped) return;
        // Mode-aware: in the default polling mode, schedule() below genuinely retries, so "still
        // trying" is true. In one-shot mode schedule()'s own !autoRefresh guard means nothing
        // further will happen on its own -- claiming otherwise would tell the person to wait for
        // something that isn't coming, when the truth is Refresh is the only way to try again.
        setPollError(
          autoRefresh
            ? 'Lost contact with the server -- still trying.'
            : "Couldn't load the timeline. Try Refresh above."
        );
        schedule();
      }
    };

    // autoRefresh mirrors ImportProgress's own schedule (see its doc comment on why the first look
    // is delayed rather than immediate). The one-shot detail-page mode has no such wait to respect
    // -- there is no in-flight job about to flip state a moment from now in the common case, and on
    // a manual refresh the person just asked for the current answer -- so it fetches right away.
    //
    // Still deferred by a macrotask rather than called synchronously, though: tick() dispatches its
    // request before its first `await`, which the `stopped` flag can't prevent (it's only checked
    // after that await resolves) -- calling it inline would fire two real requests under React
    // StrictMode's mount->cleanup->mount double-invoke, since the discarded first instance's request
    // is already in flight before cleanup runs. Routing through the same `timer`/`stop()` pair the
    // polling path already relies on means StrictMode's clearTimeout on the discarded instance
    // cancels this one too, exactly as it always has for the polling path.
    if (autoRefresh) {
      schedule();
    } else {
      timer = setTimeout(() => void tick(), 0);
    }
    return stop;
  }, [jobId, autoRefresh, refreshToken]);

  // Still nothing to poll with, or nothing recorded and nothing to explain -- both genuinely
  // render nothing IN POLLING MODE: a blip mid-poll is not a failed import, and the next
  // scheduled poll will quietly recover. One-shot mode has no next poll coming, so the same
  // silence would strand the person with no error and no hint that Refresh would fix it -- these
  // two guards below both gate on `!autoRefresh` for exactly that reason. A FAILED job is the one
  // exception to the "nothing recorded" rule regardless of mode: even with an empty stage list
  // (the stage recorder tolerates its own write failing without breaking the import, so this does
  // happen), this must still render the failure reason and the dismiss action -- ImportProgress no
  // longer offers a way back to the dropzone on its own, so this is the only path left once a job
  // fails.
  //
  // Bug fix, caught by review: both guards below used to fire regardless of mode, which leaked
  // this fix into the pre-existing polling callers it was never meant to touch -- a transient
  // blip on the live-upload screen's first poll started showing a duplicate "Lost contact with
  // the server" card next to ImportProgress's own, something the deleted-by-that-same-diff
  // comment explicitly said should never happen.
  if (!timeline) {
    if (!autoRefresh && pollError) {
      return (
        <div className="bg-card rounded-xl2 shadow-card border border-border p-6 mt-4" data-testid="import-timeline">
          <p className="text-xs text-muted">{pollError}</p>
        </div>
      );
    }
    return null;
  }
  if (timeline.stages.length === 0 && timeline.status !== 'FAILED' && !(!autoRefresh && pollError)) return null;

  const failureMessage = timeline.failureCode
    ? importFailureMessage(timeline.failureCode)
    : undefined;

  return (
    <div
      className="bg-card rounded-xl2 shadow-card border border-border p-6 mt-4"
      data-testid="import-timeline"
    >
      {timeline.stages.length > 0 && (
        <ol className="space-y-3">
          {timeline.stages.map((stage, i) => (
            <TimelineRow key={`${stage.stage}-${stage.attempt}-${i}`} stage={stage} />
          ))}
        </ol>
      )}

      {timeline.status === 'FAILED' && (
        <div className="mt-4">
          {/* warning (amber) for ACTION_REQUIRED, matching the password panel's existing color a
              few steps earlier in this same flow -- "you can fix this"; danger (red) for a plain
              FAILED the user cannot fix themselves, matching Import.tsx's own live sync-error
              banner. Sprint 4 item 22: `userStatus` (Sprint 4 item 20a) is the wire's own answer to
              which one this is -- no re-deriving it from `failureCode` here. */}
          <p
            className={`text-xs ${timeline.userStatus === 'ACTION_REQUIRED' ? 'text-warning' : 'text-danger'}`}
            data-testid="import-timeline-failure-reason"
          >
            {failureMessage ?? "Finora couldn't complete this import. Please try again."}
          </p>
          {onDismiss && (
            <button
              type="button"
              onClick={onDismiss}
              className="mt-2 text-xs font-medium text-primary hover:underline"
            >
              Try a different file
            </button>
          )}
        </div>
      )}

      {pollError && (
        <p className="text-xs text-muted mt-3">
          {/* Bug fix: this used to show the raw one-shot-mode pollError text ("Couldn't load the
              timeline...") here too, which is what a FIRST fetch failure says -- but reaching
              this line at all means a fetch already succeeded once (the stages above rendered
              from it), so the truth for a LATER failed refresh is "couldn't refresh", not
              "couldn't load", and contradicts the timeline visibly sitting right above it.
              Polling mode's text stays as-is: "still trying" is accurate there, since it will. */}
          {autoRefresh ? pollError : "Couldn't refresh -- showing the last known status."}
        </p>
      )}
    </div>
  );
}

const OUTCOME_ICON: Partial<Record<ImportTimelineStage['outcome'], typeof CheckCircle2>> = {
  COMPLETED: CheckCircle2,
  FAILED: AlertTriangle,
  RUNNING: Loader2,
  SKIPPED: MinusCircle,
};

const OUTCOME_COLOR: Partial<Record<ImportTimelineStage['outcome'], string>> = {
  COMPLETED: 'text-success',
  FAILED: 'text-warning',
  RUNNING: 'text-primary',
  SKIPPED: 'text-muted',
};

function TimelineRow({ stage }: { stage: ImportTimelineStage }) {
  // Bug fix: a Record without a fallback here means a stage.outcome value the frontend's own type
  // hasn't been updated for (drift from the backend's ImportJobStage.Outcome enum, which this
  // union isn't compiler-checked against) would resolve to `undefined` and crash `<Icon />` with
  // an invalid-element-type error -- unlike `stageLabel`, which already guards the identical class
  // of lookup with `?? 'Working'`.
  const Icon = OUTCOME_ICON[stage.outcome] ?? MinusCircle;
  const color = OUTCOME_COLOR[stage.outcome] ?? 'text-muted';
  const time = formatTime(stage.startedAt);

  return (
    <li className="flex items-center gap-3">
      <Icon
        size={16}
        className={`${color} flex-shrink-0 ${stage.outcome === 'RUNNING' ? 'animate-spin' : ''}`}
      />
      <div className="min-w-0 flex-1">
        <p className="text-sm text-ink">
          {stageLabel(stage.stage)}
          {stage.attempt > 1 && (
            <span className="text-xs text-muted ml-1.5">(attempt {stage.attempt})</span>
          )}
        </p>
      </div>
      {time && <span className="text-xs text-muted flex-shrink-0">{time}</span>}
    </li>
  );
}
