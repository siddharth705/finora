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
});
