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

  it('shows 0 before any step has completed', () => {
    // Regression test: the ring and its number must NOT show the final value before the
    // checklist it's sequenced against (AnalysisSequence) has made any progress.
    render(<HealthScoreRing step={0} totalSteps={4} />);
    expect(screen.getByText('0')).toBeInTheDocument();
    expect(screen.queryByText(String(heroScore.value))).not.toBeInTheDocument();
    // The accessible label is unconditional -- a screen reader isn't watching a scroll-triggered
    // fill animation, so it should hear the real score immediately either way.
    expect(
      screen.getByRole('img', { name: `Financial health score ${heroScore.value} out of 100` })
    ).toBeInTheDocument();
  });

  it('shows a proportional value partway through the sequence, not 0 and not the final score', () => {
    // Regression test for the actual bug reported: the ring must track the checklist's progress,
    // not stay empty until it finishes and then jump straight to 84. 2 of 4 steps -> half of 84.
    render(<HealthScoreRing step={2} totalSteps={4} />);
    expect(screen.getByText(String(Math.round((2 / 4) * heroScore.value)))).toBeInTheDocument();
    expect(screen.queryByText('0')).not.toBeInTheDocument();
    expect(screen.queryByText(String(heroScore.value))).not.toBeInTheDocument();
  });

  it('shows the final score once every step has completed', () => {
    render(<HealthScoreRing step={4} totalSteps={4} />);
    expect(screen.getByText(String(heroScore.value))).toBeInTheDocument();
  });
});
