import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { VerificationPanel } from './VerificationPanel';
import type { VerificationReport } from '../types';

/**
 * The panel's job is to be honest about what was and wasn't proven, so these tests are written
 * around the states that differ in MEANING -- not checked, checked and clean, checked and not --
 * rather than around rendering details.
 */
describe('VerificationPanel', () => {
  const verified: VerificationReport = {
    findings: [{
      rule: 'BALANCE_CHAIN',
      outcome: 'VERIFIED',
      details: { rowsChecked: 127, rowsWithBalance: 127, anchoredOnOpeningBalance: true, discrepancies: [] },
    }],
  };

  const withFindings: VerificationReport = {
    findings: [{
      rule: 'BALANCE_CHAIN',
      outcome: 'WARNING',
      details: {
        rowsChecked: 127, rowsWithBalance: 127, anchoredOnOpeningBalance: false,
        discrepancies: [
          { rowIndex: 16, expectedBalance: 54220, actualBalance: 54656, difference: 436 },
        ],
      },
    }],
  };

  it('renders nothing when verification was never performed', () => {
    // Null means not checked. A reassuring tick here would claim a check that never happened, and
    // an alarming one would report a failure that never occurred.
    const { container } = render(<VerificationPanel verification={null} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders nothing when there are no findings at all', () => {
    const { container } = render(<VerificationPanel verification={{ findings: [] }} />);
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
    // A newer backend reporting a check this build has no renderer for.
    const user = userEvent.setup();
    render(<VerificationPanel verification={{
      findings: [{ rule: 'STATEMENT_TOTALS', outcome: 'FAILED', details: { difference: 436 } }],
    }} />);

    await user.click(screen.getByRole('button', { name: /Statement verification/ }));

    expect(screen.getByText(/STATEMENT_TOTALS/)).toBeInTheDocument();
    expect(screen.getByText(/doesn't know how to display yet/)).toBeInTheDocument();
    expect(screen.queryByText(/\{/)).not.toBeInTheDocument();
  });

  it('reports a not-applicable check as unchecked rather than as a pass', async () => {
    render(<VerificationPanel verification={{
      findings: [{
        rule: 'BALANCE_CHAIN', outcome: 'NOT_APPLICABLE',
        details: { rowsChecked: 0, rowsWithBalance: 0, anchoredOnOpeningBalance: false, discrepancies: [] },
      }],
    }} />);

    expect(screen.getByText(/Couldn't be checked/)).toBeInTheDocument();
    expect(screen.queryByText(/verified/i)).not.toBeInTheDocument();
  });
});
