import type { MerchantGroup, Transaction } from '../types';

/**
 * The two halves of the categorization review backlog, as the server hands them over.
 *
 * They are disjoint by construction: `TransactionService.needsReview` explicitly filters out every
 * transaction that `TransactionGroupingService` already returned inside a merchant group, so a
 * transaction is offered either row-by-row or as part of a bulk group, never both. Mobile has to
 * render both to cover the whole backlog — showing only one silently strands the other half.
 */
export interface ReviewQueue {
  singles: Transaction[];
  groups: MerchantGroup[];
}

/**
 * How many transactions are actually waiting on a category decision.
 *
 * Counts distinct ids rather than summing the two lists. The sum is correct today given the
 * disjointness above, but this number is shown to the user as a claim about their data — and a
 * backend regression that let a grouped transaction back into the singles list would inflate it
 * with no visible symptom. Under-reporting such a regression is recoverable; overstating the
 * backlog in a track whose whole purpose is that displayed numbers can be trusted is not.
 */
export function reviewQueueCount(queue: ReviewQueue): number {
  const ids = new Set<string>();
  for (const t of queue.singles) ids.add(t.id);
  for (const g of queue.groups) for (const id of g.transactionIds) ids.add(id);
  return ids.size;
}

/**
 * The Dashboard nudge's wording. Deliberately a count of work, not a category name: the design
 * spec (docs/superpowers/specs/2026-09-01-transaction-categorization-design.md §3) draws a hard
 * line between "Other" — a real category a user chose — and "needs review", which is a queue
 * state and must never be rendered as if it were a slice of real spending.
 */
export function reviewNudgeLabel(count: number): string {
  const noun = count === 1 ? 'transaction needs' : 'transactions need';
  return `${count.toLocaleString('en-IN')} ${noun} a quick look`;
}
