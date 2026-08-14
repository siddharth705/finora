import type { ImportJobProgress } from '../api/endpoints';

/**
 * How a queued import reads to the person who uploaded it.
 *
 * **Why this is a module rather than a switch inside the component.** The lifecycle has nine states
 * and only three of them end anything; the difference between "still working" and "finished" decides
 * whether the screen keeps polling, whether Cancel is offered, and whether the review step is
 * allowed to open. Getting that wrong in a component means a spinner that never stops or a Cancel
 * button on a finished import, and neither is visible in a render test that only checks a label.
 *
 * Everything here is pure and derived from one `ImportJobProgress`, so the states that are awkward
 * to reach through the UI — FAILED after five attempts, CANCELLED mid-parse, a total that is still
 * null — are cheap to assert directly.
 */

/** The stages a worker actually holds a job in, in progression order. */
const IN_FLIGHT: ImportJobProgress['status'][] = [
  'QUEUED', 'PARSING', 'ANALYZING', 'DEDUPING', 'IMPORTING', 'LEARNING',
];

/**
 * What the user is told is happening.
 *
 * Deliberately not the enum name. "ANALYZING" is the queue's vocabulary; someone who has just
 * uploaded a bank statement is owed a sentence about their statement. The stages the worker passes
 * over still get labels, because a retry or a future worker can put a job in one of them and an
 * unlabelled state would render blank.
 */
const LABELS: Record<ImportJobProgress['status'], string> = {
  QUEUED: 'Waiting to start',
  PARSING: 'Reading your statement',
  ANALYZING: 'Finding the transactions',
  DEDUPING: 'Checking for duplicates',
  IMPORTING: 'Adding them to your account',
  LEARNING: 'Learning your merchants',
  COMPLETED: 'Ready to review',
  FAILED: "Couldn't finish",
  CANCELLED: 'Cancelled',
};

export function label(job: ImportJobProgress): string {
  return LABELS[job.status] ?? 'Working';
}

/**
 * The same sentence {@link label} gives the job's CURRENT status, for one row of the import
 * timeline instead — the stage vocabulary is the same `ImportJob.Status` enum on both, so this is
 * the identical map, not a second one to keep in sync with it.
 */
export function stageLabel(stage: string): string {
  return LABELS[stage as ImportJobProgress['status']] ?? 'Working';
}

/**
 * Whether to keep polling. Terminal means terminal — a finished job never changes again.
 *
 * Takes just the field it needs rather than the full `ImportJobProgress`, so the import timeline
 * (whose payload is a different shape but carries the identical `status` vocabulary) can reuse this
 * instead of re-deriving its own terminal-state list.
 */
export function isSettled(job: { status: ImportJobProgress['status'] }): boolean {
  return job.status === 'COMPLETED' || job.status === 'FAILED' || job.status === 'CANCELLED';
}

/**
 * Whether the import finished with something to review.
 *
 * Both halves are required, and the second is not paranoia: a job can only reach COMPLETED with a
 * session, but a client that opened the review step on the status alone would show an empty screen
 * the one time that stopped being true.
 */
export function isReviewable(job: ImportJobProgress): boolean {
  return job.status === 'COMPLETED' && job.importSessionId !== null;
}

/**
 * Whether to offer Cancel.
 *
 * Mirrors `ImportJob.isCancellable()` on the server: cancelling stops at IMPORTING, because after
 * that user-visible financial rows exist and removing them is the ledger's job, not the queue's.
 * The button is hidden rather than disabled once that line is crossed — a disabled control invites
 * the user to work out why, and the answer ("it is already writing to your accounts") is not
 * something they can act on.
 */
export function isCancellable(job: ImportJobProgress): boolean {
  const at = IN_FLIGHT.indexOf(job.status);
  return at >= 0 && at < IN_FLIGHT.indexOf('IMPORTING');
}

/**
 * How far along, 0–100, or null when that cannot honestly be said.
 *
 * Null while `rowsTotal` is null — the statement has not been counted yet, and a bar sitting at 0%
 * says "nothing is happening" when the truth is "we don't know yet". The caller renders an
 * indeterminate state for null rather than a zeroed bar.
 *
 * A completed job is 100 whatever the counts say, because the counts describe rows and the bar
 * describes the job: a statement whose rows partly failed to parse still finished.
 */
export function percent(job: ImportJobProgress): number | null {
  if (job.status === 'COMPLETED') return 100;
  if (job.rowsTotal === null || job.rowsTotal <= 0) return null;
  const done = Math.min(job.rowsProcessed, job.rowsTotal);
  return Math.round((done / job.rowsTotal) * 100);
}

/**
 * The one line of detail under the label, or null when there is nothing honest to add.
 *
 * A failed job shows the server's message: `error` is only populated once the job has actually
 * FAILED, so anything here is final rather than a transient blip mid-retry.
 */
export function detail(job: ImportJobProgress): string | null {
  if (job.status === 'FAILED') return job.error;
  if (job.rowsTotal === null) return null;
  if (job.status === 'COMPLETED') {
    return `${job.rowsTotal} ${job.rowsTotal === 1 ? 'transaction' : 'transactions'} found`;
  }
  return `${Math.min(job.rowsProcessed, job.rowsTotal)} of ${job.rowsTotal}`;
}
