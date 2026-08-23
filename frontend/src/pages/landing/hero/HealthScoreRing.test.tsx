import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { heroScore } from '../landing-config';
import { HealthScoreRing } from './HealthScoreRing';

describe('HealthScoreRing', () => {
  it('renders the score, label and delta from landing-config', () => {
    render(<HealthScoreRing />);
    expect(screen.getByText(String(heroScore.value))).toBeInTheDocument();
    expect(screen.getByText(heroScore.label)).toBeInTheDocument();
    // heroScore.delta ("+6 this month") starts with a regex-special "+" -- match the literal
    // rendered text ("↑ +6 this month") instead of building a RegExp from it.
    expect(screen.getByText(`↑ ${heroScore.delta}`)).toBeInTheDocument();
  });

  it('exposes the score as an accessible label on the ring itself', () => {
    render(<HealthScoreRing />);
    expect(
      screen.getByRole('img', { name: `Financial health score ${heroScore.value} out of 100` })
    ).toBeInTheDocument();
  });

  it('shows 0 before the checklist has started (step 0)', () => {
    render(<HealthScoreRing step={0} totalSteps={4} intervalMs={550} />);
    expect(screen.getByText('0')).toBeInTheDocument();
    expect(screen.queryByText(String(heroScore.value))).not.toBeInTheDocument();
  });

  it('starts a continuous fill from 0, not the final value, the moment the checklist starts', () => {
    // Regression test: an earlier version jumped in four discrete steps synced to each checkmark
    // (reported as not looking natural). The fill must be ONE continuous animation starting at 0
    // right when step first reaches 1, not a value already at some intermediate or final amount.
    render(<HealthScoreRing step={1} totalSteps={4} intervalMs={550} />);
    expect(screen.getByText('0')).toBeInTheDocument();
    expect(screen.queryByText(String(heroScore.value))).not.toBeInTheDocument();
  });

  it('shows the final score immediately when rendered standalone (no animation to sync against)', () => {
    render(<HealthScoreRing step={1} totalSteps={1} />);
    expect(screen.getByText(String(heroScore.value))).toBeInTheDocument();
  });
});
