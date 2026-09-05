import { fireEvent, render, screen } from '@testing-library/react-native';
import { StagedRowCard } from './StagedRowCard';
import type { DuplicateMatch, StagedRow } from '../../types';

/**
 * The review half of "no path silently unticks a row", asserted against what the user actually sees.
 *
 * `lib/importReview.test.ts` proves the state can never untick without asking. This proves the
 * question reaches the screen — because a flagged row that is excluded in state and silent in the
 * UI is exactly the old behaviour wearing new types.
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
  reason: 'Same date and amount, and a matching description, as a transaction already in your ledger.',
  ...over,
});

const row = (over: Partial<StagedRow> = {}): StagedRow => ({
  date: '2026-07-10',
  description: 'METRO FARE',
  amount: 45,
  type: 'EXPENSE',
  suggestedCategory: 'Transport',
  categorySource: 'rule',
  ruleId: null,
  likelyDuplicate: false,
  referenceNumber: null,
  balanceAfter: null,
  duplicateMatch: null,
  rowPosition: null,
  ...over,
});

const flagged = (over: Partial<StagedRow> = {}) =>
  row({ likelyDuplicate: true, duplicateMatch: match(), ...over });

function renderCard(props: Partial<React.ComponentProps<typeof StagedRowCard>> = {}) {
  const onDecide = jest.fn();
  const onApplyToSimilar = jest.fn();
  render(
    <StagedRowCard
      row={flagged()}
      included={false}
      category="Transport"
      onToggleIncluded={jest.fn()}
      onPressCategory={jest.fn()}
      decision="unresolved"
      onDecide={onDecide}
      similarUnresolved={0}
      onApplyToSimilar={onApplyToSimilar}
      {...props}
    />
  );
  return { onDecide, onApplyToSimilar };
}

describe('StagedRowCard — duplicate review', () => {
  it('puts the question and its evidence in front of the user', () => {
    renderCard();

    // Exact, not a regex: the reason sentence below also ends "...already in your ledger."
    expect(screen.getByText('Already in your ledger')).toBeTruthy();
    // The evidence, not just the claim: what it is being compared against.
    expect(screen.getByText(match().reason)).toBeTruthy();
    // Twice on purpose: the staged row and the transaction it is being compared against. Seeing
    // both at once is the comparison -- one of them alone is just an assertion the user must trust.
    expect(screen.getAllByText('METRO FARE')).toHaveLength(2);
    expect(screen.getByLabelText(/import anyway/i)).toBeTruthy();
    expect(screen.getByLabelText(/skip this row/i)).toBeTruthy();
  });

  it('reports the answer the user gave', () => {
    const { onDecide } = renderCard();
    fireEvent.press(screen.getByLabelText(/import anyway/i));
    expect(onDecide).toHaveBeenCalledWith('import');
  });

  it('reports a skip as a skip', () => {
    const { onDecide } = renderCard();
    fireEvent.press(screen.getByLabelText(/skip this row/i));
    expect(onDecide).toHaveBeenCalledWith('skip');
  });

  /** Once answered, the badge carries the outcome. Re-asking would suggest the answer had not
   *  registered. */
  it('stops asking once the row has an answer', () => {
    renderCard({ decision: 'import', included: true });
    expect(screen.queryByLabelText(/import anyway/i)).toBeNull();
    expect(screen.getByText(/duplicate — importing/i)).toBeTruthy();
  });

  it('asks nothing about a row the engine never questioned', () => {
    renderCard({ row: row(), decision: 'import', included: true });
    expect(screen.queryByText(/already in your ledger/i)).toBeNull();
    expect(screen.queryByLabelText(/import anyway/i)).toBeNull();
  });

  /**
   * A row flagged with no evidence must not render a review it cannot support. The backend derives
   * one from the other so this does not happen today; the card must not start showing an empty
   * comparison if it ever does.
   */
  it('shows no review block for a flagged row carrying no match', () => {
    renderCard({ row: row({ likelyDuplicate: true, duplicateMatch: null }) });
    expect(screen.queryByText(/already in your ledger/i)).toBeNull();
    expect(screen.queryByLabelText(/import anyway/i)).toBeNull();
  });

  describe('apply to similar', () => {
    it('is not offered while this row is itself unanswered', () => {
      renderCard({ similarUnresolved: 3 });
      expect(screen.queryByText(/apply to 3 identical rows/i)).toBeNull();
    });

    it('is offered once this row has an answer to apply', () => {
      renderCard({ decision: 'skip', similarUnresolved: 3 });
      expect(screen.getByText(/apply to 3 identical rows/i)).toBeTruthy();
    });

    it('is not offered when nothing identical is still outstanding', () => {
      renderCard({ decision: 'skip', similarUnresolved: 0 });
      expect(screen.queryByText(/apply to/i)).toBeNull();
    });

    it('reads as singular for one row', () => {
      renderCard({ decision: 'import', similarUnresolved: 1 });
      expect(screen.getByText('Apply to 1 identical row')).toBeTruthy();
    });
  });
});

/**
 * Track C/C3: isUnconfirmedGuess already surfaces the LOW-confidence sources ("Needs a look").
 * This is the other half -- naming the source for the ones with real evidence behind them, so a
 * confident suggestion doesn't read as indistinguishable from a lucky keyword match.
 */
describe('StagedRowCard — confident-source provenance (Track C/C3)', () => {
  it.each([
    ['learned', 'Learned from you'],
    ['user_rule', 'Your rule'],
    ['global_rule', 'Community rule'],
    ['file', 'From your statement'],
  ])('labels a %s row as "%s"', (categorySource, label) => {
    renderCard({ row: row({ categorySource: categorySource as StagedRow['categorySource'] }) });
    expect(screen.getByText(label)).toBeTruthy();
  });

  // The common, unremarkable path -- badging every row here would be exactly the noise "Needs a
  // look" exists to stand out from.
  it('shows no provenance badge for a plain keyword-table match', () => {
    renderCard({ row: row({ categorySource: 'rule' }) });
    expect(screen.queryByText('Learned from you')).toBeNull();
    expect(screen.queryByText('Your rule')).toBeNull();
    expect(screen.queryByText('Community rule')).toBeNull();
    expect(screen.queryByText('From your statement')).toBeNull();
  });

  // Mutually exclusive by construction (categorySource is one value) -- pinned explicitly so a
  // future categorySource added to both functions can't silently render both badges on one row.
  it.each(['default', 'structural_p2p'] as const)(
    'shows "Needs a look" rather than a provenance badge for %s',
    (categorySource) => {
      renderCard({ row: row({ categorySource }) });
      expect(screen.getByText('Needs a look')).toBeTruthy();
      expect(screen.queryByText('Learned from you')).toBeNull();
      expect(screen.queryByText('Your rule')).toBeNull();
      expect(screen.queryByText('Community rule')).toBeNull();
      expect(screen.queryByText('From your statement')).toBeNull();
    }
  );
});
