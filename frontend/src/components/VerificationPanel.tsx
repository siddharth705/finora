import { useState, type ReactNode } from 'react';
import { CheckCircle2, AlertTriangle, ChevronDown, ChevronRight, ShieldQuestion } from 'lucide-react';
import type { BalanceChainDetails, VerificationFinding, VerificationReport } from '../types';

/**
 * Shows whether an import could be proven faithful to the statement it came from.
 *
 * <b>Why this exists.</b> "127 transactions parsed" says the parser produced a result, not that the
 * result is right. A real statement imported three withdrawals as ₹0 and a deposit in the wrong
 * direction, and the preview looked entirely normal — because nothing compared the numbers to the
 * statement's own arithmetic. This panel is where that comparison becomes visible.
 *
 * <b>Deliberately shows no document-level verdict.</b> The backend removed its overall status
 * because deriving one from several rules is an aggregator's job, and no aggregator exists yet.
 * Reinventing "Verified / Warning / Failed" here would recreate exactly the second source of truth
 * that was removed — one that could disagree with the findings it claims to summarise. The heading
 * therefore describes the findings ("2 findings"), it does not judge the import.
 *
 * <b>Collapsed by default.</b> The common case is a clean import, where a single line is the whole
 * message and per-row balances are noise. Detail is one click away for the case that needs it.
 */
export function VerificationPanel({ verification }: { verification: VerificationReport | null | undefined }) {
  const [expanded, setExpanded] = useState(false);

  // Null means verification never ran -- an older import, or a path that does not check. Saying
  // nothing is honest; a reassuring tick would claim a check that never happened.
  if (!verification) return null;

  const findings = verification.findings ?? [];
  if (findings.length === 0) return null;

  const notable = findings.filter((f) => f.outcome === 'WARNING' || f.outcome === 'FAILED');
  const allClear = notable.length === 0 && findings.some((f) => f.outcome === 'VERIFIED');

  return (
    <div className="bg-card rounded-xl2 shadow-card border border-border p-4 mb-4">
      <button
        type="button"
        onClick={() => setExpanded((open) => !open)}
        aria-expanded={expanded}
        className="w-full flex items-center gap-2 text-left"
      >
        {expanded ? <ChevronDown size={15} className="text-muted flex-shrink-0" />
                  : <ChevronRight size={15} className="text-muted flex-shrink-0" />}
        <span className="text-sm font-semibold text-ink">Statement verification</span>
        <span className="ml-auto inline-flex items-center gap-1.5 text-xs">
          {allClear ? (
            <span className="inline-flex items-center gap-1.5 text-success">
              <CheckCircle2 size={14} /> Running balance verified
            </span>
          ) : notable.length > 0 ? (
            <span className="inline-flex items-center gap-1.5 text-warning">
              <AlertTriangle size={14} />
              {notable.length} {notable.length === 1 ? 'finding' : 'findings'}
            </span>
          ) : (
            <span className="inline-flex items-center gap-1.5 text-muted">
              <ShieldQuestion size={14} /> Couldn't be checked
            </span>
          )}
        </span>
      </button>

      {expanded && (
        <div className="mt-3 pt-3 border-t border-border space-y-4">
          {findings.map((finding, i) => <Finding key={`${finding.rule}-${i}`} finding={finding} />)}
        </div>
      )}
    </div>
  );
}

/**
 * Renders one finding by looking its rule up in a registry, rather than branching on the rule name
 * at each site that displays one. A new backend validator then needs one entry here and nothing
 * else — which is the same reason the wire format carries an opaque per-rule `details` payload
 * instead of a single fixed shape.
 */
function Finding({ finding }: { finding: VerificationFinding }) {
  const renderer = RULE_RENDERERS[finding.rule];
  return (
    <div>
      <p className="text-xs font-semibold text-ink mb-1.5">
        {renderer?.label ?? finding.rule}
        <OutcomeTag outcome={finding.outcome} />
      </p>
      {renderer
        ? renderer.render(finding)
        : (
          // A rule this build has no renderer for -- a newer backend, most likely. Naming it beats
          // hiding it or dumping raw JSON at the user.
          <p className="text-xs text-muted">
            This check reported an outcome this version of the app doesn't know how to display yet.
          </p>
        )}
    </div>
  );
}

function OutcomeTag({ outcome }: { outcome: VerificationFinding['outcome'] }) {
  const styles: Record<VerificationFinding['outcome'], string> = {
    VERIFIED: 'text-success',
    WARNING: 'text-warning',
    FAILED: 'text-danger',
    NOT_APPLICABLE: 'text-muted',
  };
  const labels: Record<VerificationFinding['outcome'], string> = {
    VERIFIED: 'verified',
    WARNING: 'needs review',
    FAILED: 'did not reconcile',
    NOT_APPLICABLE: 'not applicable',
  };
  return <span className={`ml-2 font-normal ${styles[outcome]}`}>· {labels[outcome]}</span>;
}

/** One entry per backend rule. Additive: a new validator adds a row here and changes nothing else. */
const RULE_RENDERERS: Record<string, { label: string; render: (f: VerificationFinding) => ReactNode }> = {
  BALANCE_CHAIN: {
    label: 'Running balance',
    render: (finding) => {
      const details = finding.details as unknown as BalanceChainDetails;
      const discrepancies = details?.discrepancies ?? [];
      return (
        <>
          <p className="text-xs text-muted">
            {details?.rowsChecked ?? 0} transaction(s) checked against the statement's own running balance.
            {' '}
            {/* Stated plainly because it is a real limit on the evidence: without an opening
                balance the first transaction has nothing before it to check against. */}
            {details?.anchoredOnOpeningBalance
              ? 'Checked from the opening balance, so the first transaction is covered too.'
              : 'The statement gave no opening balance, so the first transaction could not be checked.'}
          </p>
          {discrepancies.length > 0 && (
            <div className="mt-2 overflow-x-auto">
              <table className="text-xs w-full">
                <thead>
                  <tr className="text-muted text-left">
                    <th className="pr-3 font-medium py-1">Row</th>
                    <th className="pr-3 font-medium py-1">Expected balance</th>
                    <th className="pr-3 font-medium py-1">Statement says</th>
                    <th className="font-medium py-1">Difference</th>
                  </tr>
                </thead>
                <tbody>
                  {discrepancies.map((d) => (
                    <tr key={d.rowIndex} className="text-ink border-t border-border">
                      {/* +1 so it matches the row number a person counts on screen, not the index. */}
                      <td className="pr-3 py-1">{d.rowIndex + 1}</td>
                      <td className="pr-3 py-1">{money(d.expectedBalance)}</td>
                      <td className="pr-3 py-1">{money(d.actualBalance)}</td>
                      <td className="py-1 text-warning">{money(d.difference)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      );
    },
  },
  STATEMENT_TOTALS: {
    label: 'Statement totals',
    render: (finding) => {
      const d = finding.details as {
        reason?: string; openingBalance?: number; closingBalance?: number;
        totalCredits?: number; totalDebits?: number; expectedClosingBalance?: number;
        difference?: number; explanation?: string;
      };
      if (d?.reason) return <p className="text-xs text-muted">{d.reason}</p>;
      return (
        <>
          <p className="text-xs text-muted">
            Opening {money(d?.openingBalance)} + credits {money(d?.totalCredits)} − debits{' '}
            {money(d?.totalDebits)} = {money(d?.expectedClosingBalance)}, against a stated closing
            balance of {money(d?.closingBalance)}.
          </p>
          {/* Which of the three facts is the outlier. Without this the reader is told the statement
              does not add up and left to work out whether to distrust the rows or one header field. */}
          {d?.explanation && <p className="text-xs text-warning mt-1">{d.explanation}</p>}
        </>
      );
    },
  },
};

function money(n: number | null | undefined) {
  if (n === null || n === undefined) return '—';
  return (n < 0 ? '-₹' : '₹') + Math.abs(n).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}
