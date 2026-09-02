import type { StagedRow } from '../types';

/**
 * The review state of one staged statement — what will be imported, and what the user decided about
 * each row the engine questioned.
 *
 * **This is a port of `frontend/src/lib/importReview.ts`, deliberately kept diffable against it.**
 * The web app removed silent duplicate filtering when WI5 landed: a flagged row stopped being
 * auto-unticked and became a question the user has to answer. This app kept the old behaviour —
 * `initialInclusion` was `rows.map(r => !r.likelyDuplicate)`, with nowhere for a decision to live —
 * which left it as the last path in the product that silently unticks a row, against the milestone's
 * own criterion that no path does.
 *
 * Silent is the operative word, and it cut both ways: a genuine re-import was skipped without anyone
 * confirming it, and two identical coffees bought on the same day were skipped too.
 *
 * The invariant is structural rather than conventional. `beginReview` is the only way to start a
 * review and produces the include flags and the decisions together from one predicate, so a path
 * that unticks a row necessarily also produces the unresolved decision that blocks the import until
 * the user answers it. Every function is pure and returns a new state, so no path can half-apply a
 * decision.
 */

/**
 * Whether the engine's category for this row is a guess no human has confirmed — the rows actually
 * worth a look before confirming an import.
 *
 * Mirrors the backend's single definition, `CategorizationService.isUnconfirmedGuess`. Both clients
 * previously tested `categorySource === 'default'` inline, which was complete while "default" was
 * the only unconfirmed outcome. Adding `structural_p2p` broke that silently and in the worst
 * possible direction: person-to-person transfers are the LARGEST bucket of formerly-Other rows, so
 * they stopped rendering the "Needs a look" affordance entirely and became indistinguishable from a
 * confident keyword or learned match — on the one screen where correcting them is free.
 */
export function isUnconfirmedGuess(categorySource: string | null | undefined): boolean {
  return categorySource === 'default' || categorySource === 'structural_p2p';
}

export type DuplicateDecision = 'unresolved' | 'import' | 'skip';

export interface RowReview {
  /** Parallel to the staged rows: whether each one will be sent with `include: true`. */
  included: boolean[];
  /**
   * Parallel to the staged rows. Only rows under review are ever `'unresolved'`; every other row is
   * `'import'` from the start, so an outstanding count only ever counts questions actually asked.
   */
  decisions: DuplicateDecision[];
}

export const EMPTY_REVIEW: RowReview = { included: [], decisions: [] };

/**
 * Whether this row is one the user is being asked about.
 *
 * Keyed on `duplicateMatch`, not on `likelyDuplicate`. `likelyDuplicate` is a bare boolean;
 * `duplicateMatch` is the evidence the review screen renders. A row unticked on the strength of the
 * boolean while the screen has nothing to show for it is unticked silently *and* unanswerable — the
 * exact failure this module exists to make impossible. The backend derives one from the other, so
 * today they never disagree; this keys on the one that can actually be reviewed so they cannot start
 * to.
 */
export function isUnderReview(row: StagedRow): boolean {
  return row.duplicateMatch != null;
}

/**
 * The one way to start reviewing a set of staged rows.
 *
 * A row under review starts EXCLUDED but UNRESOLVED — not silently unticked. The untick is safe by
 * default, and it cannot take effect without a decision, because the unresolved state blocks the
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
  return rows.reduce(
    (n, row, i) => (isUnderReview(row) && decisions[i] === 'unresolved' ? n + 1 : n),
    0
  );
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
 * Applies one row's decision to every OTHER row under review with the same description that is still
 * unresolved.
 *
 * Bounded to unresolved rows deliberately: a bulk action must never overwrite a choice the user
 * already made by hand, because they would have no way to know it had happened.
 */
export function applyDecisionToSimilar(
  rows: StagedRow[],
  review: RowReview,
  index: number
): RowReview {
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
 * The preview list's own include toggle.
 *
 * Deliberately does not touch decisions: unticking a row by hand is not an answer to "is this a
 * duplicate?", and treating it as one would let the gate be released by a tap that says nothing
 * about the question being asked.
 */
export function setIncluded(review: RowReview, index: number, included: boolean): RowReview {
  return { ...review, included: review.included.map((v, i) => (i === index ? included : v)) };
}
