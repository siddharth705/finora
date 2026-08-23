import { describe, it, expect } from 'vitest';
import {
  applyDecisionToSimilar,
  beginReview,
  decide,
  setIncluded,
  toConfirmedRows,
  unresolvedCount,
  type RowReview,
} from './importReview';
import type { DuplicateMatch, StagedRow } from '../types';

/**
 * The rule this module exists to make structural: **no path silently unticks a row.**
 *
 * It used to be a convention. The single-account review screen honoured it after WI5; the
 * multi-account one did not, because it held `included: boolean[]` with nowhere for a decision to
 * live, so the untick happened and nothing recorded that the user had never been asked. The tests
 * below are written against the invariant rather than against either screen, because the invariant
 * is what a third staging path (an async import resuming a session, say) has to inherit.
 */

const match: DuplicateMatch = {
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
};

function row(description: string, flagged: boolean, overrides: Partial<StagedRow> = {}): StagedRow {
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
    duplicateMatch: flagged ? { ...match, existingDescription: description } : null,
    ...overrides,
  } as StagedRow;
}

/**
 * The acceptance test, as an assertion rather than a scenario: whatever the rows are, a row that
 * starts unticked is a row the user is being asked about and has not yet answered.
 */
function assertNoSilentUntick(rows: StagedRow[], review: RowReview) {
  rows.forEach((r, i) => {
    if (review.included[i] === false) {
      expect(review.decisions[i], `row ${i} (${r.description}) was unticked`).toBe('unresolved');
      expect(r.duplicateMatch, `row ${i} (${r.description}) was unticked with nothing to review`).not.toBeNull();
    }
  });
}

describe('beginReview — nothing is unticked without a question attached to it', () => {
  it('starts a flagged row excluded AND unresolved, never merely excluded', () => {
    const rows = [row('METRO FARE', true), row('BLINKIT 9982', false)];

    const review = beginReview(rows);

    expect(review.included).toEqual([false, true]);
    expect(review.decisions).toEqual(['unresolved', 'import']);
    assertNoSilentUntick(rows, review);
  });

  it('leaves a clean statement fully included and fully resolved, so it is never gated', () => {
    const rows = [row('BLINKIT 9982', false), row('BLUE TOKAI', false)];

    const review = beginReview(rows);

    expect(review.included).toEqual([true, true]);
    expect(unresolvedCount(rows, review.decisions)).toBe(0);
    assertNoSilentUntick(rows, review);
  });

  /**
   * The hole the old two-array construction left open. `likelyDuplicate` is a bare boolean and
   * `duplicateMatch` is the evidence the review screen renders; the pre-WI5 code unticked on the
   * boolean and resolved on the evidence, so a row carrying one without the other would have been
   * unticked with nothing on screen to answer. Both now key on the reviewable one.
   */
  it('does not untick a row it has no evidence to show the user', () => {
    const rows = [row('METRO FARE', true, { duplicateMatch: null })];

    const review = beginReview(rows);

    expect(review.included).toEqual([true]);
    expect(review.decisions).toEqual(['import']);
    assertNoSilentUntick(rows, review);
  });
});

describe('decide', () => {
  it('ticks the row back on for "import" and records the answer', () => {
    const rows = [row('METRO FARE', true)];

    const after = decide(rows, beginReview(rows), 0, 'import');

    expect(after.included).toEqual([true]);
    expect(after.decisions).toEqual(['import']);
    expect(unresolvedCount(rows, after.decisions)).toBe(0);
  });

  it('keeps the row off for "skip", but as an answer rather than a default', () => {
    const rows = [row('METRO FARE', true)];

    const after = decide(rows, beginReview(rows), 0, 'skip');

    expect(after.included).toEqual([false]);
    expect(after.decisions).toEqual(['skip']);
    expect(unresolvedCount(rows, after.decisions)).toBe(0);
  });

  it('touches only the row decided, so two accounts in one file cannot answer for each other', () => {
    const rows = [row('METRO FARE', true), row('SWIGGY 4471', true)];

    const after = decide(rows, beginReview(rows), 0, 'import');

    expect(after.decisions).toEqual(['import', 'unresolved']);
    expect(after.included).toEqual([true, false]);
  });
});

describe('applyDecisionToSimilar', () => {
  it('reaches unanswered rows with the same description and nothing else', () => {
    const rows = [row('METRO FARE', true), row('METRO FARE', true), row('SWIGGY 4471', true)];
    const review = decide(rows, beginReview(rows), 0, 'import');

    const after = applyDecisionToSimilar(rows, review, 0);

    expect(after.decisions).toEqual(['import', 'import', 'unresolved']);
    expect(after.included).toEqual([true, true, false]);
  });

  it('never overwrites a choice already made by hand', () => {
    const rows = [row('METRO FARE', true), row('METRO FARE', true), row('METRO FARE', true)];
    let review = decide(rows, beginReview(rows), 2, 'skip');
    review = decide(rows, review, 0, 'import');

    const after = applyDecisionToSimilar(rows, review, 0);

    expect(after.decisions).toEqual(['import', 'import', 'skip']);
    expect(after.included).toEqual([true, true, false]);
  });

  it('does nothing from a row that has not been decided yet', () => {
    const rows = [row('METRO FARE', true), row('METRO FARE', true)];
    const review = beginReview(rows);

    expect(applyDecisionToSimilar(rows, review, 0)).toEqual(review);
  });
});

describe('setIncluded', () => {
  /** Unticking a row by hand is not an answer to "is this a duplicate?". Treating it as one would
   *  let the gate be released by a click that says nothing about the question being asked. */
  it('changes what is imported without resolving anything', () => {
    const rows = [row('METRO FARE', true)];
    const review = beginReview(rows);

    const after = setIncluded(review, 0, true);

    expect(after.included).toEqual([true]);
    expect(after.decisions).toEqual(['unresolved']);
    expect(unresolvedCount(rows, after.decisions)).toBe(1);
  });
});

describe('unresolvedCount', () => {
  it('counts only flagged rows that still need a decision', () => {
    const rows = [row('METRO FARE', true), row('BLINKIT 9982', false), row('SWIGGY 4471', true)];

    expect(unresolvedCount(rows, ['unresolved', 'import', 'import'])).toBe(1);
  });

  it('is zero when there are no duplicates at all, so a clean import is never gated', () => {
    const rows = [row('BLINKIT 9982', false), row('BLUE TOKAI', false)];

    expect(unresolvedCount(rows, ['import', 'import'])).toBe(0);
  });
});

describe('toConfirmedRows', () => {
  /**
   * The field the multi-account path used to drop. Without it the row lands in the ledger and
   * reconciliation immediately marks it a duplicate again, so the user's decision shows in the
   * ledger and vanishes from every spend total (see V65 / commit 55f2db0).
   */
  it('tells the server exactly which duplicates the user personally cleared', () => {
    const rows = [row('METRO FARE', true), row('SWIGGY 4471', true), row('BLINKIT 9982', false)];
    let review = decide(rows, beginReview(rows), 0, 'import');
    review = decide(rows, review, 1, 'skip');

    const payload = toConfirmedRows(rows, review, ['Transport', 'Dining', 'Groceries']);

    expect(payload.map((r) => [r.description, r.include, r.confirmedNotDuplicate])).toEqual([
      ['METRO FARE', true, true],
      ['SWIGGY 4471', false, false],
      // Never flagged, so there was no question to answer -- claiming a decision the user was
      // never asked to make would let any client opt rows out of duplicate detection for free.
      ['BLINKIT 9982', true, false],
    ]);
  });

  it('carries the staged fields review does not own', () => {
    const rows = [
      row('METRO FARE', false, {
        ruleId: 'rule-1', referenceNumber: 'REF/9981', balanceAfter: 1200.5, categoryConfidence: 70,
      }),
    ];

    const payload = toConfirmedRows(rows, beginReview(rows), ['Transport']);

    expect(payload[0]).toMatchObject({
      date: '2026-07-10',
      amount: 45,
      type: 'EXPENSE',
      category: 'Transport',
      categorySource: 'rule',
      ruleId: 'rule-1',
      likelyDuplicate: false,
      referenceNumber: 'REF/9981',
      balanceAfter: 1200.5,
      categoryConfidence: 70,
    });
  });
});
