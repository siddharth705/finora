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

  it('shows 0, not the final score, while drawn=false', () => {
    // Regression test: the ring and its number must NOT show the final value before the caller
    // (AnalysisSequence) says the sequence it's gated behind has actually finished -- otherwise
    // "84" reads as arriving for no reason rather than as the conclusion of that sequence.
    render(<HealthScoreRing drawn={false} />);
    expect(screen.getByText('0')).toBeInTheDocument();
    expect(screen.queryByText(String(heroScore.value))).not.toBeInTheDocument();
    // The accessible label is unconditional -- a screen reader isn't watching a scroll-triggered
    // draw animation, so it should hear the real score immediately either way.
    expect(
      screen.getByRole('img', { name: `Financial health score ${heroScore.value} out of 100` })
    ).toBeInTheDocument();
  });

  it('shows the final score once drawn=true', () => {
    render(<HealthScoreRing drawn={true} />);
    expect(screen.getByText(String(heroScore.value))).toBeInTheDocument();
  });
});
