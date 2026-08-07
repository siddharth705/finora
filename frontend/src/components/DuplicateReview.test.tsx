import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { DuplicateReview, unresolvedCount, type DuplicateDecision } from './DuplicateReview';
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

describe('unresolvedCount', () => {
  it('counts only flagged rows that still need a decision', () => {
    const rows = [row(), clean(), row()];
    const decisions: DuplicateDecision[] = ['unresolved', 'import', 'import'];

    expect(unresolvedCount(rows, decisions)).toBe(1);
  });

  it('is zero when there are no duplicates at all, so a clean import is never gated', () => {
    expect(unresolvedCount([clean(), clean()], ['import', 'import'])).toBe(0);
  });
});

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

  /** The safety message is part of the contract, not decoration: the user needs to know that
   *  looking at this screen has not changed anything yet. */
  it('states that nothing changes until the import is confirmed', () => {
    render(
      <DuplicateReview rows={[row()]} decisions={['unresolved']} onDecide={vi.fn()} onApplyToSimilar={vi.fn()} />
    );

    expect(screen.getByText(/nothing changes in your ledger until you confirm/)).toBeInTheDocument();
  });
});
