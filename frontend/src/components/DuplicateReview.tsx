import type { StagedRow } from '../types';
import { unresolvedCount, type DuplicateDecision } from '../lib/importReview';
import { formatDateDDMMMYYYY } from '../utils/date';

/**
 * The last decision point before Finora writes anything into a user's ledger (WI5).
 *
 * Duplicate detection used to be a filter: a flagged row was silently unticked and, unless the
 * user noticed a checkbox they had never touched, it simply did not import. That is wrong in both
 * directions. A genuine re-import got skipped without anyone confirming it should be, and two
 * identical coffees bought on the same day got skipped as well — the system deciding something it
 * has no way to know.
 *
 * So the system presents what it found, and the user decides. Concretely:
 *
 * - **Nothing is pre-decided.** A flagged row starts UNRESOLVED, not unticked. The import is
 *   blocked until every one has an explicit answer, which is the only way "I didn't mean to skip
 *   that" stops being possible.
 * - **Both sides are shown.** The staged row and the transaction it appears to repeat, side by
 *   side, with the reason. A flag the user has to take on trust is not a decision they can make.
 * - **Each action says what it does.** "Import anyway" and "Skip" rather than a checkbox whose
 *   consequence is inferred from its position in a table.
 *
 * `confidence` is not rendered as a score, even though it now carries two distinct values
 * (`"EXACT"` for CSV/PDF's date+amount+description match, `"LIKELY"` for a Gmail receipt matched
 * against the bank ledger by amount + date window + merchant-name similarity, since a receipt's
 * description can never be textually identical to a bank line — see `DuplicateMatch`'s own doc).
 * A percentage or badge would invite treating "LIKELY" as a lesser-trust flag to dismiss rather
 * than read; `match.reason` already says, in plain language, what was actually compared, which is
 * the same "no automatic filter" principle this whole component exists for, applied to the
 * confidence value itself.
 *
 * **This component renders a review; it does not own one.** The decision state machine, and the
 * rule that a row may only start unticked if it also starts unresolved, live in
 * `lib/importReview.ts` — because the multi-account path holds one review per detected account and
 * this component is rendered once per account against it. Putting the state here would have meant
 * either duplicating it per section or special-casing the component, which is exactly what the
 * multi-account gap needed restructuring to avoid.
 */

/** Re-exported for consumers that render this component; the definition lives with the state
 *  machine in lib/importReview.ts. */
export type { DuplicateDecision };

function formatMoney(amount: number) {
  return (amount < 0 ? '-₹' : '₹') + Math.abs(amount).toLocaleString('en-IN', { maximumFractionDigits: 2 });
}

function formatWhen(iso: string) {
  return new Date(iso).toLocaleDateString('en-IN', { dateStyle: 'medium' });
}

/**
 * One duplicate, both sides, and two explicit choices.
 *
 * Deliberately not a modal. A user resolving five of these should see them as a list they work
 * through, not five interruptions — and a modal hides the rest of the review while it is open,
 * which is the context needed to judge whether a repeat is plausible.
 */
function DuplicatePair({
  row,
  index,
  decision,
  onDecide,
  onApplyToSimilar,
  similarCount,
}: {
  row: StagedRow;
  index: number;
  decision: DuplicateDecision;
  onDecide: (decision: DuplicateDecision) => void;
  onApplyToSimilar: () => void;
  similarCount: number;
}) {
  const match = row.duplicateMatch!;

  return (
    <li className="border border-border rounded-lg p-3 space-y-3" data-testid={`duplicate-${index}`}>
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
        <div className="rounded-md bg-surface p-2">
          <p className="text-[10px] uppercase text-muted tracking-wide">In this statement</p>
          <p className="text-sm font-medium">{row.description}</p>
          <p className="text-xs text-muted">
            {formatDateDDMMMYYYY(row.date)} · {formatMoney(row.amount)}
          </p>
        </div>
        <div className="rounded-md bg-surface p-2">
          <p className="text-[10px] uppercase text-muted tracking-wide">Already in your ledger</p>
          <p className="text-sm font-medium">{match.existingDescription}</p>
          <p className="text-xs text-muted">
            {formatDateDDMMMYYYY(match.existingDate)} · {formatMoney(match.existingAmount)} · imported{' '}
            {formatWhen(match.existingImportedAt)}
          </p>
        </div>
      </div>

      <p className="text-xs text-muted">{match.reason}</p>

      {/* More than one existing match usually means this genuinely recurs, so the honest framing
          is "you have several of these already", not "this is even more certainly a duplicate". */}
      {match.matchCount > 1 && (
        <p className="text-xs" style={{ color: '#d97706' }}>
          You already have {match.matchCount} transactions matching this. If this is something you
          pay repeatedly, importing is probably right.
        </p>
      )}

      <div className="flex flex-wrap items-center gap-2">
        <button
          type="button"
          onClick={() => onDecide('import')}
          aria-pressed={decision === 'import'}
          className={`px-3 py-1.5 rounded-md text-xs font-semibold border ${
            decision === 'import'
              ? 'bg-primary text-white border-primary'
              : 'border-border hover:bg-surface'
          }`}
        >
          Import anyway
        </button>
        <button
          type="button"
          onClick={() => onDecide('skip')}
          aria-pressed={decision === 'skip'}
          className={`px-3 py-1.5 rounded-md text-xs font-semibold border ${
            decision === 'skip' ? 'bg-ink text-white border-ink' : 'border-border hover:bg-surface'
          }`}
        >
          Skip this row
        </button>

        {/* Only offered once a decision exists -- "apply to similar" with nothing to apply is a
            button that cannot do anything, and offering it invites a click that silently no-ops. */}
        {decision !== 'unresolved' && similarCount > 0 && (
          <button
            type="button"
            onClick={onApplyToSimilar}
            className="px-3 py-1.5 rounded-md text-xs border border-border hover:bg-surface"
          >
            Apply to {similarCount} similar
          </button>
        )}

        {decision === 'unresolved' && (
          <span className="text-xs text-danger">Needs a decision</span>
        )}
      </div>
    </li>
  );
}

/**
 * The review list, shown only when there is something to review.
 *
 * @param onApplyToSimilar applies one row's decision to every other unresolved duplicate whose
 *        description matches. Bounded to unresolved rows on purpose: a bulk action must never
 *        overwrite a choice the user already made by hand.
 */
export function DuplicateReview({
  rows,
  decisions,
  onDecide,
  onApplyToSimilar,
}: {
  rows: StagedRow[];
  decisions: DuplicateDecision[];
  onDecide: (index: number, decision: DuplicateDecision) => void;
  onApplyToSimilar: (index: number) => void;
}) {
  const flagged = rows
    .map((row, index) => ({ row, index }))
    .filter(({ row }) => row.duplicateMatch);

  if (flagged.length === 0) return null;

  const outstanding = unresolvedCount(rows, decisions);

  const similarTo = (index: number) =>
    flagged.filter(
      ({ row, index: other }) =>
        other !== index &&
        decisions[other] === 'unresolved' &&
        row.description === rows[index].description
    ).length;

  return (
    <section className="border border-border rounded-xl p-4 space-y-3" data-testid="duplicate-review">
      <div>
        <h3 className="text-sm font-semibold">
          {flagged.length} possible duplicate{flagged.length === 1 ? '' : 's'}
        </h3>
        <p className="text-xs text-muted mt-1">
          These look like transactions already in your ledger. Nothing is imported or skipped until
          you decide — and nothing changes in your ledger until you confirm the import.
        </p>
      </div>

      {outstanding > 0 ? (
        <p className="text-xs text-danger" role="status">
          {outstanding} still {outstanding === 1 ? 'needs' : 'need'} a decision before you can
          import.
        </p>
      ) : (
        <p className="text-xs" style={{ color: '#15803d' }} role="status">
          All duplicates resolved.
        </p>
      )}

      <ul className="space-y-2">
        {flagged.map(({ row, index }) => (
          <DuplicatePair
            key={index}
            row={row}
            index={index}
            decision={decisions[index]}
            onDecide={(d) => onDecide(index, d)}
            onApplyToSimilar={() => onApplyToSimilar(index)}
            similarCount={similarTo(index)}
          />
        ))}
      </ul>
    </section>
  );
}
