import type { StagedRow } from '../types';
import type { ConfirmedRowPayload } from '../api/endpoints';

/**
 * The review state of one staged statement — what will be imported, and what the user decided
 * about each row the engine questioned.
 *
 * **Why this is a module rather than two `useState` calls on a page.** WI5 removed silent duplicate
 * filtering from the single-account review screen: a flagged row stops being auto-unticked and
 * becomes a question the user has to answer. The multi-account (multi-section) path did not get
 * that, and kept the old behaviour — `included: rows.map(r => !r.likelyDuplicate)` with no decision
 * tracked anywhere — for one structural reason: its review state is per detected account, so there
 * was nowhere for a decision to live. The two paths held the same three arrays in two shapes, and
 * only one of them was ever wired to the review screen.
 *
 * So the fix is not a second copy of the review component. It is making the inclusion flags and
 * the duplicate decisions **impossible to construct separately**: `beginReview` is the only way to
 * start a review, and it produces both at once from the same predicate. A path that wants to untick
 * a row necessarily also produces the unresolved decision that blocks the import until the user
 * answers it. That is what "no path silently unticks a row" means as code rather than as a
 * convention someone has to remember on the next screen that stages rows.
 *
 * Every function here is pure and returns a new state — no path can half-apply a decision.
 */

export type DuplicateDecision = 'unresolved' | 'import' | 'skip';

export interface RowReview {
  /** Parallel to the staged rows: whether each one will be sent with `include: true`. */
  included: boolean[];
  /**
   * Parallel to the staged rows. Only rows under review are ever `'unresolved'`; every other row
   * is `'import'` from the start, so an outstanding count only ever counts questions actually
   * asked.
   */
  decisions: DuplicateDecision[];
}

export const EMPTY_REVIEW: RowReview = { included: [], decisions: [] };

/**
 * Whether this row is one the user is being asked about.
 *
 * Keyed on `duplicateMatch`, not on `likelyDuplicate`, and the two must not be allowed to disagree
 * here. `likelyDuplicate` is a bare boolean; `duplicateMatch` is the evidence the review screen
 * renders. A row unticked on the strength of the boolean while the screen has nothing to show for
 * it is unticked silently *and* unanswerable — the exact failure this module exists to make
 * impossible. The backend derives one from the other (`TransactionNormalizer`), so today they never
 * disagree; this keys on the one that can actually be reviewed so that they cannot start to.
 */
function isUnderReview(row: StagedRow): boolean {
  return row.duplicateMatch != null;
}

/**
 * The one way to start reviewing a set of staged rows.
 *
 * A row under review starts EXCLUDED but UNRESOLVED — not silently unticked. The untick is safe by
 * default, and it cannot take effect without a decision because the unresolved state blocks the
 * confirm button. Every other row starts included and needs no answer.
 */
export function beginReview(rows: StagedRow[]): RowReview {
  return {
    included: rows.map((row) => !isUnderReview(row)),
    decisions: rows.map((row) => (isUnderReview(row) ? 'unresolved' : 'import')),
  };
}

/** How many rows the user still has to answer before this set of rows may be imported. */
export function unresolvedCount(rows: StagedRow[], decisions: DuplicateDecision[]): number {
  return rows.reduce((n, row, i) => (isUnderReview(row) && decisions[i] === 'unresolved' ? n + 1 : n), 0);
}

/** Records one decision and syncs that row's include flag, so the confirm payload stays the single
 *  source of truth about what actually gets imported. */
export function decide(
  rows: StagedRow[],
  review: RowReview,
  index: number,
  decision: DuplicateDecision
): RowReview {
  if (!rows[index]) return review;
  return {
    included: review.included.map((v, i) => (i === index ? decision === 'import' : v)),
    decisions: review.decisions.map((v, i) => (i === index ? decision : v)),
  };
}

/**
 * Applies one row's decision to every OTHER row under review with the same description that is
 * still unresolved.
 *
 * Bounded to unresolved rows deliberately: a bulk action must never overwrite a choice the user
 * already made by hand, because they would have no way to know it had happened.
 */
export function applyDecisionToSimilar(rows: StagedRow[], review: RowReview, index: number): RowReview {
  const decision = review.decisions[index];
  if (decision === 'unresolved' || !rows[index]) return review;
  const description = rows[index].description;

  const reached = (i: number) =>
    i !== index &&
    review.decisions[i] === 'unresolved' &&
    !!rows[i] &&
    isUnderReview(rows[i]) &&
    rows[i].description === description;

  return {
    included: review.included.map((v, i) => (reached(i) ? decision === 'import' : v)),
    decisions: review.decisions.map((v, i) => (reached(i) ? decision : v)),
  };
}

/**
 * The preview table's own include checkbox.
 *
 * Deliberately does not touch decisions: unticking a row by hand is not an answer to "is this a
 * duplicate?", and treating it as one would let the gate be released by a click that says nothing
 * about the question being asked.
 */
export function setIncluded(review: RowReview, index: number, included: boolean): RowReview {
  return { ...review, included: review.included.map((v, i) => (i === index ? included : v)) };
}

/**
 * One confirm payload's worth of rows, for every path that confirms an import — single account,
 * re-import, and each section of a multi-account statement.
 *
 * Shared rather than written per call site because a field dropped here is silent: the import
 * succeeds and the ledger is quietly missing something. That is not hypothetical on this file's
 * history — the multi-account path built its own row payload and omitted `confirmedNotDuplicate`,
 * so even once it had a review screen the user's "this is not a duplicate" would have been honoured
 * in the ledger and reversed by the next reconciliation pass. Same class of loss as the mobile
 * app's own `buildRowPayload`, and the same reason it is one tested function there too.
 */
export function toConfirmedRows(
  rows: StagedRow[],
  review: RowReview,
  chosenCategory: string[]
): ConfirmedRowPayload[] {
  return rows.map((r, i) => ({
    date: r.date,
    description: r.description,
    amount: r.amount,
    type: r.type,
    category: chosenCategory[i],
    include: review.included[i],
    categorySource: r.categorySource,
    // Without this, decision_rule_id would always land null through the normal UI flow -- the
    // backend derives decisionSource from categorySource alone, but the specific rule link only
    // survives if the staged ruleId is echoed back here, same as categorySource already was.
    ruleId: r.ruleId,
    categoryConfidence: r.categoryConfidence,
    likelyDuplicate: r.likelyDuplicate,
    referenceNumber: r.referenceNumber,
    balanceAfter: r.balanceAfter,
    // The user's answer, not the engine's guess. Without it, reconciliation re-flags the row the
    // moment it lands and strips it from every spend total -- the decision would show in the ledger
    // and vanish from the numbers. Only ever true for a row the engine actually questioned: a
    // client cannot claim a decision the user was never asked to make. See ImportDto.ConfirmedRow.
    confirmedNotDuplicate: isUnderReview(r) && review.decisions[i] === 'import',
  }));
}
