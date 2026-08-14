import { describe, it, expect } from 'vitest';
import { detail, isCancellable, isReviewable, isSettled, label, percent } from './importJob';
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
    status: 'QUEUED',
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
