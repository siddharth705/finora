import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { VerificationPanel } from './VerificationPanel';
import type { VerificationFinding, VerificationReport } from '../types';

/**
 * The panel's job is to be honest about what was and wasn't proven, so these tests are written
 * around the states that differ in MEANING -- not checked, checked and clean, checked and not --
 * rather than around rendering details.
 */
describe('VerificationPanel', () => {
  // reliabilityStatus null throughout this helper -- these tests exercise the LEGACY fallback
  // badge logic (allClear/notable), which only runs when the server never computed a status. The
  // server-computed badge has its own coverage below.
  const report = (findings: VerificationFinding[]): VerificationReport => ({
    findings, headerReconstructionUncertain: false, textSource: null, reliabilityStatus: null,
  });

  const verified: VerificationReport = report([{
    rule: 'BALANCE_CHAIN',
    outcome: 'VERIFIED',
    details: { rowsChecked: 127, rowsWithBalance: 127, anchoredOnOpeningBalance: true, discrepancies: [] },
  }]);

  const withFindings: VerificationReport = report([{
    rule: 'BALANCE_CHAIN',
    outcome: 'WARNING',
    details: {
      rowsChecked: 127, rowsWithBalance: 127, anchoredOnOpeningBalance: false,
      discrepancies: [
        { rowIndex: 16, expectedBalance: 54220, actualBalance: 54656, difference: 436 },
      ],
    },
  }]);

  it('renders nothing when verification was never performed', () => {
    // Null means not checked. A reassuring tick here would claim a check that never happened, and
    // an alarming one would report a failure that never occurred.
    const { container } = render(<VerificationPanel verification={null} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders nothing when there are no findings at all', () => {
    const { container } = render(<VerificationPanel verification={report([])} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('summarises a clean result without needing to be expanded', () => {
    render(<VerificationPanel verification={verified} />);

    expect(screen.getByText(/Running balance verified/)).toBeInTheDocument();
    // Collapsed by default: the per-row detail is noise on an import that reconciles.
    expect(screen.queryByText(/54,220/)).not.toBeInTheDocument();
  });

  it('reports how many findings there are, without judging the import overall', () => {
    render(<VerificationPanel verification={withFindings} />);

    expect(screen.getByText(/1 finding/)).toBeInTheDocument();
    // No document-level verdict -- the backend deliberately has no aggregator, and inventing one
    // here would be a second source of truth that could disagree with the findings.
    expect(screen.queryByText(/^Failed$/)).not.toBeInTheDocument();
    expect(screen.queryByText(/^Verified$/)).not.toBeInTheDocument();
  });

  it('reveals the offending row only once expanded', async () => {
    const user = userEvent.setup();
    render(<VerificationPanel verification={withFindings} />);

    await user.click(screen.getByRole('button', { name: /Statement verification/ }));

    // Row 17 as a person counts it, from rowIndex 16.
    expect(screen.getByText('17')).toBeInTheDocument();
    expect(screen.getByText(/54,220\.00/)).toBeInTheDocument();
    expect(screen.getByText(/54,656\.00/)).toBeInTheDocument();
  });

  it('collapses again on a second click', async () => {
    const user = userEvent.setup();
    render(<VerificationPanel verification={withFindings} />);
    const toggle = screen.getByRole('button', { name: /Statement verification/ });

    await user.click(toggle);
    expect(screen.getByText(/54,220\.00/)).toBeInTheDocument();

    await user.click(toggle);
    expect(screen.queryByText(/54,220\.00/)).not.toBeInTheDocument();
  });

  it('says plainly when the first row could not be covered', async () => {
    // A real limit on the evidence, not implementation detail: without an opening balance the
    // first transaction has nothing before it to check against, and saying "verified" flatly
    // would overstate what was proven.
    const user = userEvent.setup();
    render(<VerificationPanel verification={withFindings} />);

    await user.click(screen.getByRole('button', { name: /Statement verification/ }));

    expect(screen.getByText(/first transaction could not be checked/)).toBeInTheDocument();
  });

  it('names an unknown rule instead of hiding it or dumping raw data', async () => {
    // A newer backend reporting a check this build has no renderer for. Deliberately a rule that
    // does not exist yet. This test has now been rewritten twice, first off STATEMENT_TOTALS and
    // then off COLUMN_AMBIGUITY, each time because the rule it named stopped being unknown when
    // that validator shipped -- which is the behaviour under test working, not a flaky test.
    const user = userEvent.setup();
    render(<VerificationPanel verification={report(
      [{ rule: 'CURRENCY_CONSISTENCY', outcome: 'FAILED', details: { row: 17 } }],
    )} />);

    await user.click(screen.getByRole('button', { name: /Statement verification/ }));

    expect(screen.getByText(/CURRENCY_CONSISTENCY/)).toBeInTheDocument();
    expect(screen.getByText(/doesn't know how to display yet/)).toBeInTheDocument();
    expect(screen.queryByText(/\{/)).not.toBeInTheDocument();
  });

  it('reports a not-applicable check as unchecked rather than as a pass', async () => {
    render(<VerificationPanel verification={report([{
      rule: 'BALANCE_CHAIN', outcome: 'NOT_APPLICABLE',
      details: { rowsChecked: 0, rowsWithBalance: 0, anchoredOnOpeningBalance: false, discrepancies: [] },
    }])} />);

    expect(screen.getByText(/Couldn't be checked/)).toBeInTheDocument();
    expect(screen.queryByText(/verified/i)).not.toBeInTheDocument();
  });
  it("shows the bank's own totals beside ours, and only what the bank printed", async () => {
    // The value of this rule is that its evidence is external, so the panel shows both sides. Only
    // the fields the statement actually printed appear -- here it gave counts but no totals, and a
    // row of dashes for the totals would read as a comparison that was made and passed.
    const user = userEvent.setup();
    render(<VerificationPanel verification={report([{
      rule: 'SUMMARY_TOTALS', outcome: 'FAILED',
      details: {
        printedCreditCount: 1, parsedCreditCount: 0,
        printedDebitCount: 3, parsedDebitCount: 4,
        suspectedCause: 'DIRECTION',
        explanation: 'At least one is being read as money moving the wrong way.',
      },
    }])} />);

    await user.click(screen.getByRole('button', { name: /Statement verification/ }));

    expect(screen.getByText(/The bank's own totals/)).toBeInTheDocument();
    expect(screen.getByText(/wrong way/)).toBeInTheDocument();
    expect(screen.queryByText(/Money in/)).not.toBeInTheDocument();
  });

  it('shows the server-computed status badge, not the client-side fallback, when one is present', () => {
    // findings are all VERIFIED here -- if the panel were still computing its own badge, this
    // would render "Running balance verified" too, and the test wouldn't distinguish the two
    // code paths. NEEDS_ATTENTION with clean findings only happens via header-reconstruction/OCR
    // provenance, which is exactly the case a client-side findings-only heuristic cannot see.
    render(<VerificationPanel verification={{
      findings: [{
        rule: 'BALANCE_CHAIN', outcome: 'VERIFIED',
        details: { rowsChecked: 1, rowsWithBalance: 1, anchoredOnOpeningBalance: true, discrepancies: [] },
      }],
      headerReconstructionUncertain: true, textSource: 'NATIVE_PDF', reliabilityStatus: 'NEEDS_ATTENTION',
    }} />);

    expect(screen.getByText(/Import needs attention/)).toBeInTheDocument();
    expect(screen.queryByText(/Running balance verified/)).not.toBeInTheDocument();
  });

  it('explains an OCR-driven review status with a visible reason, not just the badge', async () => {
    const user = userEvent.setup();
    render(<VerificationPanel verification={{
      findings: [{
        rule: 'BALANCE_CHAIN', outcome: 'VERIFIED',
        details: { rowsChecked: 1, rowsWithBalance: 1, anchoredOnOpeningBalance: true, discrepancies: [] },
      }],
      headerReconstructionUncertain: false, textSource: 'OCR', reliabilityStatus: 'REVIEW_RECOMMENDED',
    }} />);

    expect(screen.getByText(/Imported with notes/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Statement verification/ }));
    // Without this line, an OCR-only REVIEW_RECOMMENDED would show a badge with every finding
    // below it VERIFIED -- inexplicable, since OCR provenance isn't a finding at all.
    expect(screen.getByText(/read using OCR/)).toBeInTheDocument();
  });
});
