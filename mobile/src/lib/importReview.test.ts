import {
  applyDecisionToSimilar, beginReview, decide, isUnderReview, setIncluded, unresolvedCount,
} from './importReview';
import type { DuplicateMatch, StagedRow } from '../types';

/**
 * The rule this module exists to make structural: **no path silently unticks a row.**
 *
 * This app was the last one that did. `initialInclusion` was `rows.map(r => !r.likelyDuplicate)`,
 * with nowhere for a decision to live, so a flagged row was excluded whether or not anyone read it
 * and the import could be confirmed without the question ever being put. The web app removed that
 * behaviour when WI5 landed; these tests are the same invariant, asserted the same way.
 *
 * Written against the invariant rather than against the screen, because the invariant is what any
 * future staging path has to inherit.
 */

const match = (over: Partial<DuplicateMatch> = {}): DuplicateMatch => ({
  existingTransactionId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
  existingAccountId: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
  existingDate: '2026-07-10',
  existingDescription: 'METRO FARE',
  existingAmount: 45,
  existingType: 'EXPENSE',
  existingImportedAt: '2026-07-11T09:00:00Z',
  matchCount: 1,
  confidence: 'EXACT',
  reason: 'Same date, amount and description as a transaction already in your ledger.',
  ...over,
});

function row(description: string, flagged: boolean, over: Partial<StagedRow> = {}): StagedRow {
  return {
    date: '2026-07-10',
    description,
    amount: 45,
    type: 'EXPENSE',
    suggestedCategory: 'Transport',
    categorySource: 'rule',
    ruleId: null,
    likelyDuplicate: flagged,
    referenceNumber: null,
    balanceAfter: null,
    duplicateMatch: flagged ? match({ existingDescription: description }) : null,
    ...over,
  };
}

describe('beginReview', () => {
  it('never unticks a row without also recording that nobody has answered for it', () => {
    const rows = [row('METRO FARE', true), row('BLINKIT', false)];
    const review = beginReview(rows);

    // The untick is safe by default...
    expect(review.included).toEqual([false, true]);
    // ...and cannot take effect silently, because this blocks the import.
    expect(review.decisions).toEqual(['unresolved', 'import']);
    expect(unresolvedCount(rows, review.decisions)).toBe(1);
  });

  it('asks nothing about a statement with no duplicates in it', () => {
    const rows = [row('BLINKIT', false), row('SWIGGY', false)];
    const review = beginReview(rows);
    expect(review.included).toEqual([true, true]);
    expect(unresolvedCount(rows, review.decisions)).toBe(0);
  });

  /**
   * Keyed on the evidence, not the boolean. A row unticked on the strength of `likelyDuplicate`
   * while the screen has nothing to render is unticked silently AND unanswerable — the exact
   * failure the module exists to prevent. The backend derives one from the other so they never
   * disagree today; this proves the module cannot start unticking on the unreviewable one.
   */
  it('does not put a row under review when there is no evidence to review', () => {
    const rows = [row('METRO FARE', true, { duplicateMatch: null })];
    expect(isUnderReview(rows[0])).toBe(false);
    expect(beginReview(rows).included).toEqual([true]);
    expect(unresolvedCount(rows, beginReview(rows).decisions)).toBe(0);
  });
});

describe('decide', () => {
  it('releases the gate and imports the row when the user says it is not a duplicate', () => {
    const rows = [row('METRO FARE', true)];
    const after = decide(rows, beginReview(rows), 0, 'import');
    expect(after.included).toEqual([true]);
    expect(unresolvedCount(rows, after.decisions)).toBe(0);
  });

  it('releases the gate and leaves the row out when the user skips it', () => {
    const rows = [row('METRO FARE', true)];
    const after = decide(rows, beginReview(rows), 0, 'skip');
    expect(after.included).toEqual([false]);
    expect(unresolvedCount(rows, after.decisions)).toBe(0);
  });
});

describe('setIncluded', () => {
  /**
   * Unticking a row by hand is not an answer to "is this a duplicate?". Treating it as one would
   * let the gate be released by a tap that says nothing about the question being asked.
   */
  it('does not answer the duplicate question', () => {
    const rows = [row('METRO FARE', true)];
    const after = setIncluded(beginReview(rows), 0, true);
    expect(after.included).toEqual([true]);
    expect(unresolvedCount(rows, after.decisions)).toBe(1);
  });
});

describe('applyDecisionToSimilar', () => {
  it('answers every identical row still outstanding', () => {
    const rows = [row('METRO FARE', true), row('METRO FARE', true), row('SWIGGY', true)];
    const answered = decide(rows, beginReview(rows), 0, 'import');
    const after = applyDecisionToSimilar(rows, answered, 0);

    expect(after.decisions).toEqual(['import', 'import', 'unresolved']);
    expect(after.included).toEqual([true, true, false]);
  });

  /** A bulk action must never overwrite a choice made by hand — the user would have no way to know
   *  it had happened. */
  it('leaves a row the user already answered alone', () => {
    const rows = [row('METRO FARE', true), row('METRO FARE', true)];
    let review = decide(rows, beginReview(rows), 1, 'skip');
    review = decide(rows, review, 0, 'import');

    const after = applyDecisionToSimilar(rows, review, 0);
    expect(after.decisions).toEqual(['import', 'skip']);
    expect(after.included).toEqual([true, false]);
  });

  it('does nothing when the row it is applied from is itself unanswered', () => {
    const rows = [row('METRO FARE', true), row('METRO FARE', true)];
    const review = beginReview(rows);
    expect(applyDecisionToSimilar(rows, review, 0)).toEqual(review);
  });
});
