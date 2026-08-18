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
 * <b>Shows a document-level verdict now, but a server-computed one.</b> The backend originally had
 * no overall status because deriving one from several rules is an aggregator's job, and none
 * existed. CORRECTED: `reliabilityStatus` is that aggregator now, computed once on the server from
 * facts the findings below already carry (a finding's own outcome, header-reconstruction
 * uncertainty, OCR provenance) -- never a weight, never a score. This panel renders that value
 * rather than computing its own, which is a fix for the exact "second source of truth" risk this
 * comment used to warn about: the badge below used to be an ephemeral client-side guess; now it
 * is the one canonical answer, displayed, not reinvented. The fallback branch (`reliabilityStatus`
 * null) keeps the old client-side heuristic alive only for a report from before this field existed.
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
  // Legacy fallback only -- used when the server never computed reliabilityStatus (a report from
  // before this field existed). Left as ephemeral client logic on purpose: it must not be
  // strengthened into a permanent second implementation of the same derivation.
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
          {verification.reliabilityStatus === 'CLEAN' ? (
            <span className="inline-flex items-center gap-1.5 text-success">
              <CheckCircle2 size={14} /> Imported successfully
            </span>
          ) : verification.reliabilityStatus === 'NEEDS_ATTENTION' ? (
            <span className="inline-flex items-center gap-1.5 text-danger">
              <AlertTriangle size={14} /> Import needs attention
            </span>
          ) : verification.reliabilityStatus === 'REVIEW_RECOMMENDED' ? (
            // Deliberately not "review recommended" -- reads as something went wrong. This status
            // fires on OCR alone as often as on an actual finding, and most OCR reads are fine.
            <span className="inline-flex items-center gap-1.5 text-warning">
              <AlertTriangle size={14} /> Imported with notes
            </span>
          ) : allClear ? (
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
          {/* The one fact that explains a REVIEW_RECOMMENDED status when every finding below is
              otherwise clean -- OCR provenance lives on the report itself, not as a finding, so
              without this line an OCR-only "review recommended" verdict has nothing visible to
              point at. */}
          {(verification.textSource === 'OCR' || verification.textSource === 'NATIVE_PLUS_OCR') && (
            <p className="text-xs text-muted">
              This statement was read using OCR (scanned-image recognition), not the document's own
              text. Recognition can misread characters in ways a text-based read cannot.
            </p>
          )}
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
  SUMMARY_TOTALS: {
    label: "The bank's own totals",
    render: (finding) => {
      const d = finding.details as {
        reason?: string; explanation?: string;
        printedDebitTotal?: number; parsedDebitTotal?: number;
        printedCreditTotal?: number; parsedCreditTotal?: number;
        printedDebitCount?: number; parsedDebitCount?: number;
        printedCreditCount?: number; parsedCreditCount?: number;
      };
      if (d?.reason) return <p className="text-xs text-muted">{d.reason}</p>;

      // Only rows the statement actually printed. A row of dashes against our own figure would
      // read as a comparison that was made, when in fact there was nothing to compare against.
      const rows: { label: string; printed?: number; parsed?: number; money: boolean }[] = [
        { label: 'Money in', printed: d?.printedCreditTotal, parsed: d?.parsedCreditTotal, money: true },
        { label: 'Money out', printed: d?.printedDebitTotal, parsed: d?.parsedDebitTotal, money: true },
        { label: 'Credits', printed: d?.printedCreditCount, parsed: d?.parsedCreditCount, money: false },
        { label: 'Debits', printed: d?.printedDebitCount, parsed: d?.parsedDebitCount, money: false },
      ].filter((r) => r.printed !== undefined);

      return (
        <>
          <p className="text-xs text-muted mb-2">
            Compared against the totals printed on the statement itself.
          </p>
          <div className="overflow-x-auto">
            <table className="text-xs w-full">
              <thead>
                <tr className="text-muted text-left">
                  <th className="pr-3 font-medium py-1"></th>
                  <th className="pr-3 font-medium py-1">Statement says</th>
                  <th className="font-medium py-1">Imported</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => {
                  const agrees = r.printed === r.parsed;
                  return (
                    <tr key={r.label} className="text-ink border-t border-border">
                      <td className="pr-3 py-1 text-muted">{r.label}</td>
                      <td className="pr-3 py-1">{r.money ? money(r.printed) : r.printed}</td>
                      <td className={`py-1 ${agrees ? '' : 'text-warning'}`}>
                        {r.money ? money(r.parsed) : r.parsed}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          {/* What KIND of mistake, not just that there was one -- a count that disagrees while the
              money matches means something very different from the reverse, and sends the reader
              somewhere different. */}
          {d?.explanation && <p className="text-xs text-warning mt-2">{d.explanation}</p>}
        </>
      );
    },
  },
  COLUMN_AMBIGUITY: {
    label: 'Rows that could be read two ways',
    render: (finding) => {
      const d = finding.details as {
        reason?: string; explanation?: string; rowsChecked?: number; ambiguousRows?: number;
        ambiguities?: { rowIndex: number; kind: string; column: string; value: string }[];
      };
      if (d?.reason) return <p className="text-xs text-muted">{d.reason}</p>;
      const rows = d?.ambiguities ?? [];
      if (rows.length === 0) {
        return (
          <p className="text-xs text-muted">
            Every transaction's amount and direction was stated by the document, not assumed.
          </p>
        );
      }
      return (
        <>
          {d?.explanation && <p className="text-xs text-muted">{d.explanation}</p>}
          <div className="mt-2 overflow-x-auto">
            <table className="text-xs w-full">
              <thead>
                <tr className="text-muted text-left">
                  <th className="pr-3 font-medium py-1">Row</th>
                  <th className="pr-3 font-medium py-1">Column</th>
                  <th className="font-medium py-1">What the document had</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((a) => (
                  <tr key={`${a.rowIndex}-${a.column}`} className="text-ink border-t border-border">
                    {/* +1 so it matches the row number a person counts on screen. */}
                    <td className="pr-3 py-1">{a.rowIndex + 1}</td>
                    <td className="pr-3 py-1 text-muted">{a.column}</td>
                    <td className="py-1 text-warning">{a.value}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      );
    },
  },
};

function money(n: number | null | undefined) {
  if (n === null || n === undefined) return '—';
  return (n < 0 ? '-₹' : '₹') + Math.abs(n).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}
