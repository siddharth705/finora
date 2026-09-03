import { describe, it, expect } from 'vitest';
import { detail, isCancellable, isReviewable, isSettled, label, percent, recentImportsRefetchIntervalMs } from './importJob';
import type { ImportJobProgress } from '../api/endpoints';

/**
 * The lifecycle rules the progress screen depends on, asserted away from the screen.
 *
 * Several of these states are expensive or impossible to reach through the UI — FAILED only after
 * five attempts, CANCELLED only in the window a worker holds the job, a null `rowsTotal` only in the
 * seconds before the statement has been counted. A render test that drives the real flow would
 * exercise two of the nine states and quietly leave the rest to hope.
 */

function job(over: Partial<ImportJobProgress> = {}): ImportJobProgress {
  return {
    jobId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
    fileName: 'statement.csv',
    status: 'QUEUED',
    userStatus: 'PROCESSING',
    rowsTotal: null,
    rowsProcessed: 0,
    createdAt: '2026-08-08T09:00:00Z',
    startedAt: null,
    finishedAt: null,
    importSessionId: null,
    error: null,
    correlationId: null,
    ...over,
  };
}

describe('importJob — when to stop polling', () => {
  it('keeps polling through every stage a worker holds a job in', () => {
    for (const status of ['QUEUED', 'PARSING', 'ANALYZING', 'DEDUPING', 'IMPORTING', 'LEARNING'] as const) {
      expect(isSettled(job({ status }))).toBe(false);
    }
  });

  it('stops on every terminal state, not just the happy one', () => {
    // FAILED and CANCELLED are the ones worth naming: a poller that only stopped on COMPLETED
    // would spin forever on the two outcomes the user is most likely to be watching.
    expect(isSettled(job({ status: 'COMPLETED' }))).toBe(true);
    expect(isSettled(job({ status: 'FAILED' }))).toBe(true);
    expect(isSettled(job({ status: 'CANCELLED' }))).toBe(true);
  });
});

/**
 * Bug fix, caught by review: "Recent Imports" used to have nothing keeping it current for someone
 * who stayed on Statement History the whole time a watched job finished -- staleTime/window-focus
 * refetching only helps on remount or tab refocus, neither of which happens for an already-open,
 * already-focused page. Tested directly rather than only through React Query's own
 * refetchInterval callback, which would need simulated timers to exercise at all.
 */
describe('importJob — how often "Recent Imports" should refetch itself', () => {
  it('keeps refetching while anything listed is still in flight', () => {
    expect(recentImportsRefetchIntervalMs([job({ status: 'QUEUED' })])).toBe(15_000);
    expect(recentImportsRefetchIntervalMs([job({ status: 'ANALYZING' })])).toBe(15_000);
  });

  it('stops once every listed job has settled', () => {
    expect(recentImportsRefetchIntervalMs([job({ status: 'FAILED' })])).toBe(false);
    expect(recentImportsRefetchIntervalMs([job({ status: 'COMPLETED' }), job({ status: 'CANCELLED' })])).toBe(false);
  });

  it('keeps refetching if even one of several listed jobs is still in flight', () => {
    expect(recentImportsRefetchIntervalMs([job({ status: 'COMPLETED' }), job({ status: 'PARSING' })])).toBe(15_000);
  });

  it('has nothing to watch for an empty list', () => {
    expect(recentImportsRefetchIntervalMs([])).toBe(false);
  });
});

describe('importJob — when there is something to review', () => {
  it('is ready only when the job both finished and left a session behind', () => {
    expect(isReviewable(job({ status: 'COMPLETED', importSessionId: 'session-1' }))).toBe(true);
  });

  it('is not ready on a completed job with no session', () => {
    // The exact shape the worker used to produce: COMPLETED, rows counted, nothing staged. Opening
    // the review step on the status alone would show an empty screen.
    expect(isReviewable(job({ status: 'COMPLETED', importSessionId: null }))).toBe(false);
  });

  it('is not ready on a cancelled job that happens to carry a session', () => {
    expect(isReviewable(job({ status: 'CANCELLED', importSessionId: 'session-1' }))).toBe(false);
  });
});

describe('importJob — when cancelling is still honest', () => {
  it('offers cancel up to the point of no return', () => {
    for (const status of ['QUEUED', 'PARSING', 'ANALYZING', 'DEDUPING'] as const) {
      expect(isCancellable(job({ status }))).toBe(true);
    }
  });

  it('withdraws it once financial rows exist', () => {
    // Mirrors ImportJob.isCancellable() on the server. Offering a button the server will refuse is
    // worse than not offering one: the user reads the refusal as a bug rather than as a boundary.
    expect(isCancellable(job({ status: 'IMPORTING' }))).toBe(false);
    expect(isCancellable(job({ status: 'LEARNING' }))).toBe(false);
  });

  it('withdraws it from anything already finished', () => {
    expect(isCancellable(job({ status: 'COMPLETED' }))).toBe(false);
    expect(isCancellable(job({ status: 'FAILED' }))).toBe(false);
    expect(isCancellable(job({ status: 'CANCELLED' }))).toBe(false);
  });
});

describe('importJob — how far along', () => {
  it('declines to guess before the statement has been counted', () => {
    // Null, not 0. A bar pinned at zero says "nothing is happening"; the truth is "we don't know
    // yet", and the caller renders an indeterminate state for exactly this.
    expect(percent(job({ status: 'PARSING', rowsTotal: null }))).toBeNull();
  });

  it('reports the fraction once there is a total', () => {
    expect(percent(job({ status: 'ANALYZING', rowsTotal: 200, rowsProcessed: 50 }))).toBe(25);
  });

  it('never exceeds 100, even if the counts disagree', () => {
    expect(percent(job({ status: 'ANALYZING', rowsTotal: 10, rowsProcessed: 99 }))).toBe(100);
  });

  it('is complete when the job is, whatever the row counts say', () => {
    // A statement whose rows partly failed to parse still finished. The bar describes the job.
    expect(percent(job({ status: 'COMPLETED', rowsTotal: 200, rowsProcessed: 3 }))).toBe(100);
  });
});

describe('importJob — what the user is told', () => {
  it('describes the statement, not the queue', () => {
    expect(label(job({ status: 'ANALYZING' }))).toBe('Finding the transactions');
    expect(label(job({ status: 'QUEUED' }))).toBe('Waiting to start');
  });

  it('has nothing to add on a failure -- ImportTimeline owns the reason now', () => {
    // Bug fix: this used to return the raw job.error, which meant a customer saw it permanently
    // disagreeing with ImportTimeline's curated reason for every failure without a curated
    // ErrorCode. FAILED has nothing honest left to add here.
    expect(detail(job({ status: 'FAILED', error: 'That PDF is password protected.' }))).toBeNull();
  });

  it('says nothing about counts it does not have', () => {
    expect(detail(job({ status: 'PARSING', rowsTotal: null }))).toBeNull();
  });

  it('counts transactions rather than rows once it is done', () => {
    expect(detail(job({ status: 'COMPLETED', rowsTotal: 1, rowsProcessed: 1 })))
      .toBe('1 transaction found');
    expect(detail(job({ status: 'COMPLETED', rowsTotal: 42, rowsProcessed: 42 })))
      .toBe('42 transactions found');
  });
});

/**
 * The trust hold, which differs from the review hold in one way that matters here: it has a real
 * `importSessionId` behind it. The parse succeeded and rows were staged; what is withheld is the
 * user's confirm step, because the extraction's own evidence says the rows may be wrong.
 *
 * That makes the staged session the thing to be careful about. Every other held state got here by
 * failing and has nothing to offer; this one has a complete, plausible-looking import sitting one
 * click away, and the entire feature is the promise that the click is not available.
 */
describe('importJob — held for trust review', () => {
  const trustHeld = () => job({
    status: 'HELD_FOR_TRUST_REVIEW',
    userStatus: 'HELD_FOR_REVIEW',
    importSessionId: 'session-that-must-not-be-confirmable',
  });

  it('never offers the review step, even though a staged session exists', () => {
    // The gate. isReviewable checks `status === 'COMPLETED'`, so this holds by construction --
    // which is exactly why it is worth pinning: a later "held jobs are basically complete"
    // convenience would hand the user the import this feature exists to withhold.
    expect(trustHeld().importSessionId).not.toBeNull();
    expect(isReviewable(trustHeld())).toBe(false);
  });

  it('stops polling, because it waits on a person and not on the worker', () => {
    expect(isSettled(trustHeld())).toBe(true);
  });

  it('is not offered a Cancel button', () => {
    expect(isCancellable(trustHeld())).toBe(false);
  });

  it('reads as work in progress rather than as a failure', () => {
    expect(label(trustHeld())).toBe('Running additional checks');
  });

  it('reads exactly like the other hold, because the user is in the same situation', () => {
    // The internal distinction is ours, not theirs: nothing is running, nothing is theirs to fix.
    const otherHold = job({ status: 'HELD_FOR_REVIEW', userStatus: 'HELD_FOR_REVIEW' });
    expect(label(trustHeld())).toBe(label(otherHold));
    expect(detail(trustHeld())).toBe(detail(otherHold));
  });

  it('promises no deadline and never questions the statement itself', () => {
    // Binding harder here than on the other hold: the doubt really is about the document's
    // contents, so this is the message most at risk of leaking "we are checking your statement".
    const text = detail(trustHeld()) ?? '';
    for (const forbidden of ['hour', 'minute', 'day', 'soon', 'shortly', 'within']) {
      expect(text.toLowerCase()).not.toContain(forbidden);
    }
    for (const forbidden of ['genuine', 'authentic', 'verify', 'legitimate', 'fraud', 'suspicious']) {
      expect(text.toLowerCase()).not.toContain(forbidden);
    }
  });

  it('tells the user there is nothing for them to do', () => {
    expect(detail(trustHeld())?.toLowerCase()).toContain('no action needed');
  });
});

describe('importJob — held for review', () => {
  const held = () => job({ status: 'HELD_FOR_REVIEW', userStatus: 'HELD_FOR_REVIEW' });

  it('stops polling, because a held job waits on a person and not on the worker', () => {
    // Triage is manual and can take days. Polling through it would spin a browser tab indefinitely
    // for an update that arrives by push and email instead.
    expect(isSettled(held())).toBe(true);
  });

  it('is not offered a Cancel button', () => {
    expect(isCancellable(held())).toBe(false);
  });

  it('is not reviewable — there is nothing staged to review', () => {
    expect(isReviewable(held())).toBe(false);
  });

  it('reads as work in progress rather than as a failure', () => {
    expect(label(held())).toBe('Running additional checks');
  });

  it('explains itself even though no rows were ever counted', () => {
    // The rowsTotal guard below this branch would otherwise swallow the message entirely: a held
    // job usually failed before counting a single row, so the user would get a bare label.
    expect(held().rowsTotal).toBeNull();
    expect(detail(held())).toContain('additional checks');
  });

  it('promises no deadline and never questions the statement itself', () => {
    const text = detail(held()) ?? '';
    // Both halves are product decisions, not phrasing preferences -- see the copy's own comment.
    for (const forbidden of ['hour', 'minute', 'day', 'soon', 'shortly', 'within']) {
      expect(text.toLowerCase()).not.toContain(forbidden);
    }
    for (const forbidden of ['genuine', 'authentic', 'verify', 'legitimate', 'fraud', 'suspicious']) {
      expect(text.toLowerCase()).not.toContain(forbidden);
    }
  });

  it('tells the user there is nothing for them to do', () => {
    expect(detail(held())?.toLowerCase()).toContain('no action needed');
  });

  it('shows no progress percentage — nothing is running', () => {
    expect(percent(held())).toBeNull();
  });

  it('does not keep a recent-imports list refetching', () => {
    expect(recentImportsRefetchIntervalMs([held()])).toBe(false);
  });
});
