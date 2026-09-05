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
  HELD_FOR_REVIEW: 'Running additional checks',
  // Identical wording to the hold above, on purpose. The two states differ in why we are looking,
  // never in what the user is waiting for, and a distinct label would invite them to work out the
  // difference -- which they cannot, and which is not theirs to worry about.
  HELD_FOR_TRUST_REVIEW: 'Running additional checks',
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
  return job.status === 'COMPLETED' || job.status === 'FAILED'
    || job.status === 'HELD_FOR_REVIEW' || job.status === 'HELD_FOR_TRUST_REVIEW'
    || job.status === 'CANCELLED';
}

/**
 * Whether a job ended by being handed to a person instead of finishing on its own — the two
 * triage holds, `HELD_FOR_REVIEW` (a parser gap) and `HELD_FOR_TRUST_REVIEW` (the extraction's own
 * evidence didn't add up). Both are terminal per {@link isSettled}, but unlike a genuine FAILED or
 * CANCELLED outcome, a held job is not actually over — the person is owed the same "stay on screen
 * and read why" treatment a FAILED job already gets, not a silent reset to the empty dropzone.
 */
export function isHeld(job: { status: ImportJobProgress['status'] }): boolean {
  return job.status === 'HELD_FOR_REVIEW' || job.status === 'HELD_FOR_TRUST_REVIEW';
}

/**
 * How often StatementHistory's "Recent Imports" list should refetch, in ms, or `false` to stop --
 * while ANY listed job hasn't reached a terminal state, since that's the only reason to keep
 * polling at all. A page with nothing in flight (the common case) pays no ongoing cost.
 *
 * Bug fix, caught by review: without this, a job that finished or failed while the user stayed on
 * this page the whole time (never remounting it) kept showing its last-fetched in-flight status
 * indefinitely -- React Query's own `staleTime`/`refetchOnWindowFocus` only affect fetch-on-mount
 * and focus events, neither of which happens for someone just sitting on an already-open page.
 *
 * A named, exported function rather than an inline callback passed to `useQuery`'s
 * `refetchInterval` option so this decision has a direct unit test instead of only being
 * exercisable by simulating React Query's own timer internals.
 */
export function recentImportsRefetchIntervalMs(jobs: { status: ImportJobProgress['status'] }[]): number | false {
  return jobs.some((j) => !isSettled(j)) ? 15_000 : false;
}

/**
 * Whether the import finished with something to review.
 *
 * Both halves are required, and the second is not paranoia: a job can only reach COMPLETED with a
 * session, but a client that opened the review step on the status alone would show an empty screen
 * the one time that stopped being true.
 *
 * A type predicate, not a plain boolean: ImportDetail.tsx's "Review this import" action needs
 * `job.importSessionId` as a non-null `string` to navigate with, and this is the one place that
 * already knows it's safe -- narrowing here means that caller doesn't need its own unchecked `!`
 * assertion repeating the same guarantee.
 */
export function isReviewable(job: ImportJobProgress): job is ImportJobProgress & { importSessionId: string } {
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
 * Bug fix, caught by a post-ship review: this used to return `job.error` for a FAILED job --
 * `ImportJob.lastError`, raw `ExceptionClass: message` text `ErrorCode`'s own doc calls "never fit
 * to show a customer directly." Before the import timeline (Premium Import Reliability v1, §3.1)
 * existed, that raw string was still the ONLY account of the failure on screen, so showing it was
 * the least-bad option. Now `ImportTimeline` renders alongside this component for the entire time
 * a FAILED job stays mounted and owns a curated, translated failure reason -- so this returning
 * `job.error` too meant a customer saw both at once, permanently, disagreeing with each other in
 * tone and content for every failure without a curated `ErrorCode`. FAILED now has nothing to add
 * here; the reason belongs to `ImportTimeline` alone.
 */
export function detail(job: ImportJobProgress): string | null {
  if (job.status === 'FAILED') return null;
  // Ahead of the rowsTotal guard below, because a held job usually never got far enough to
  // count a row -- falling through would leave the user with a bare label and no
  // explanation of why nothing is moving.
  //
  // The wording is deliberate on two counts. No ETA: triage is manual and volume-dependent,
  // so a promised deadline would start breaking the moment volume grew. And no suggestion
  // that the statement's authenticity is in question -- the cause is on our side (a parser
  // gap for HELD_FOR_REVIEW, our own extraction contradicting itself for
  // HELD_FOR_TRUST_REVIEW), and telling someone their own bank statement is being checked
  // for genuineness is a worse trust hit than the delay it would excuse. That second rule
  // binds harder for the trust hold, where the doubt really is about what the document says.
  // It also has to stay true: additional checks genuinely are run, by a person, before the
  // import proceeds.
  if (job.status === 'HELD_FOR_REVIEW' || job.status === 'HELD_FOR_TRUST_REVIEW') {
    return "We need to run some additional checks on this statement before we can complete "
      + "the import. We'll notify you once it's ready \u2014 no action needed from you right now.";
  }
  if (job.rowsTotal === null) return null;
  if (job.status === 'COMPLETED') {
    return `${job.rowsTotal} ${job.rowsTotal === 1 ? 'transaction' : 'transactions'} found`;
  }
  return `${Math.min(job.rowsProcessed, job.rowsTotal)} of ${job.rowsTotal}`;
}
