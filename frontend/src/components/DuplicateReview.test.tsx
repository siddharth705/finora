import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { DuplicateReview } from './DuplicateReview';
import type { StagedRow, DuplicateMatch } from '../types';

/**
 * WI5's contract, which is a safety contract rather than a presentational one: no transaction is
 * imported or skipped on the system's own initiative.
 *
 * The behaviour being replaced was a silent filter. A flagged row was unticked automatically and,
 * unless the user noticed a checkbox they had never touched, it simply did not import — wrong in
 * both directions, because two identical coffees bought on the same day were skipped just as
 * quietly as a genuine re-import.
 */

const match: DuplicateMatch = {
  existingTransactionId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
  existingAccountId: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
  existingDate: '2026-07-10',
  existingDescription: 'SWIGGY ORDER 4471',
  existingAmount: 486,
  existingType: 'EXPENSE',
  existingImportedAt: '2026-07-11T09:00:00Z',
  matchCount: 1,
  confidence: 'EXACT',
  reason: 'Same date, amount and description as a transaction already in your ledger.',
};

function row(overrides: Partial<StagedRow> = {}): StagedRow {
  return {
    date: '2026-07-10',
    description: 'SWIGGY ORDER 4471',
    amount: 486,
    type: 'EXPENSE',
    suggestedCategory: 'Dining',
    categorySource: 'rule',
    ruleId: null,
    likelyDuplicate: true,
    referenceNumber: null,
    balanceAfter: null,
    duplicateMatch: match,
    ...overrides,
  } as StagedRow;
}

const clean = () => row({ description: 'BLINKIT 9982', likelyDuplicate: false, duplicateMatch: null });

// `unresolvedCount` moved to lib/importReview.ts along with the rest of the decision state machine
// (this component renders a review, it does not own one) -- see importReview.test.ts for its tests.

describe('DuplicateReview', () => {
  it('renders nothing when no row is flagged', () => {
    const { container } = render(
      <DuplicateReview rows={[clean()]} decisions={['import']} onDecide={vi.fn()} onApplyToSimilar={vi.fn()} />
    );
    expect(container).toBeEmptyDOMElement();
  });

  /** Both sides, or the user is being asked to trust a flag rather than make a decision. */
  it('shows the staged row against the transaction it appears to repeat', () => {
    render(
      <DuplicateReview rows={[row()]} decisions={['unresolved']} onDecide={vi.fn()} onApplyToSimilar={vi.fn()} />
    );

    expect(screen.getByText('In this statement')).toBeInTheDocument();
    expect(screen.getByText('Already in your ledger')).toBeInTheDocument();
    expect(screen.getByText(/Same date, amount and description/)).toBeInTheDocument();
    // When the existing one was imported: the strongest signal for "did I already load this
    // statement?", which is the question the user is actually trying to answer.
    expect(screen.getByText(/· imported/)).toBeInTheDocument();
  });

  /** Both dates in the pair use the same fixed display format as the import review table
   *  (formatDateDDMMMYYYY) rather than the raw ISO value or a locale-dependent rendering. */
  it('renders both dates as DD-MMM-YYYY', () => {
    render(
      <DuplicateReview rows={[row()]} decisions={['unresolved']} onDecide={vi.fn()} onApplyToSimilar={vi.fn()} />
    );

    expect(screen.getAllByText(/10-Jul-2026/)).toHaveLength(2);
  });

  it('says how many decisions are still outstanding', () => {
    render(
      <DuplicateReview
        rows={[row(), row({ description: 'UBER TRIP 8891' })]}
        decisions={['unresolved', 'unresolved']}
        onDecide={vi.fn()}
        onApplyToSimilar={vi.fn()}
      />
    );

    expect(screen.getByText(/2 still need a decision/)).toBeInTheDocument();
  });

  it('confirms when everything has been decided', () => {
    render(
      <DuplicateReview rows={[row()]} decisions={['import']} onDecide={vi.fn()} onApplyToSimilar={vi.fn()} />
    );

    expect(screen.getByText('All duplicates resolved.')).toBeInTheDocument();
  });

  it('reports both decisions explicitly rather than as a checkbox', async () => {
    const onDecide = vi.fn();
    render(
      <DuplicateReview rows={[row()]} decisions={['unresolved']} onDecide={onDecide} onApplyToSimilar={vi.fn()} />
    );

    await userEvent.click(screen.getByRole('button', { name: 'Import anyway' }));
    expect(onDecide).toHaveBeenCalledWith(0, 'import');

    await userEvent.click(screen.getByRole('button', { name: 'Skip this row' }));
    expect(onDecide).toHaveBeenCalledWith(0, 'skip');
  });

  /**
   * Several existing matches means the user probably transacts this repeatedly — a daily fare, a
   * split bill. A filter would read that as "even more certainly a duplicate"; it is the opposite,
   * and the copy has to say so or the count actively misleads.
   */
  it('treats several existing matches as a reason to import, not a stronger duplicate signal', () => {
    render(
      <DuplicateReview
        rows={[row({ duplicateMatch: { ...match, matchCount: 3 } })]}
        decisions={['unresolved']}
        onDecide={vi.fn()}
        onApplyToSimilar={vi.fn()}
      />
    );

    expect(screen.getByText(/already have 3 transactions matching this/)).toBeInTheDocument();
    expect(screen.getByText(/importing is probably right/)).toBeInTheDocument();
  });

  /** Offering a bulk action with nothing to apply invites a click that silently does nothing. */
  it('offers apply-to-similar only once a decision exists and there is something to apply it to', async () => {
    const rows = [row(), row()];

    const { rerender } = render(
      <DuplicateReview rows={rows} decisions={['unresolved', 'unresolved']} onDecide={vi.fn()} onApplyToSimilar={vi.fn()} />
    );
    expect(screen.queryByRole('button', { name: /Apply to/ })).not.toBeInTheDocument();

    const onApplyToSimilar = vi.fn();
    rerender(
      <DuplicateReview rows={rows} decisions={['skip', 'unresolved']} onDecide={vi.fn()} onApplyToSimilar={onApplyToSimilar} />
    );

    await userEvent.click(screen.getByRole('button', { name: 'Apply to 1 similar' }));
    expect(onApplyToSimilar).toHaveBeenCalledWith(0);
  });

  /** A long duplicate list should not force clicking "Skip this row" one at a time -- but with
   *  only one outstanding row, a bulk button would be a second way to do the exact same click. */
  describe('bulk resolve (onDecideAll)', () => {
    it('is not offered when onDecideAll is omitted', () => {
      render(
        <DuplicateReview
          rows={[row(), row({ description: 'UBER TRIP 8891' })]}
          decisions={['unresolved', 'unresolved']}
          onDecide={vi.fn()}
          onApplyToSimilar={vi.fn()}
        />
      );

      expect(screen.queryByRole('button', { name: /remaining/ })).not.toBeInTheDocument();
    });

    it('is not offered when at most one row is outstanding', () => {
      render(
        <DuplicateReview
          rows={[row(), row({ description: 'UBER TRIP 8891' })]}
          decisions={['import', 'unresolved']}
          onDecide={vi.fn()}
          onApplyToSimilar={vi.fn()}
          onDecideAll={vi.fn()}
        />
      );

      expect(screen.queryByRole('button', { name: /remaining/ })).not.toBeInTheDocument();
    });

    it('skips or imports everything still outstanding, in one click', async () => {
      const onDecideAll = vi.fn();
      render(
        <DuplicateReview
          rows={[row(), row({ description: 'UBER TRIP 8891' }), row({ description: 'ZOMATO 221' })]}
          decisions={['unresolved', 'unresolved', 'unresolved']}
          onDecide={vi.fn()}
          onApplyToSimilar={vi.fn()}
          onDecideAll={onDecideAll}
        />
      );

      await userEvent.click(screen.getByRole('button', { name: 'Skip all remaining' }));
      expect(onDecideAll).toHaveBeenCalledWith('skip');

      await userEvent.click(screen.getByRole('button', { name: 'Import all remaining' }));
      expect(onDecideAll).toHaveBeenCalledWith('import');
    });
  });

  /** The safety message is part of the contract, not decoration: the user needs to know that
   *  looking at this screen has not changed anything yet. */
  it('states that nothing changes until the import is confirmed', () => {
    render(
      <DuplicateReview rows={[row()]} decisions={['unresolved']} onDecide={vi.fn()} onApplyToSimilar={vi.fn()} />
    );

    expect(screen.getByText(/nothing changes in your ledger until you confirm/)).toBeInTheDocument();
  });
});
