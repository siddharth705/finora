import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { heroIntelligence } from '../landing-config';
import { IntelligenceScan } from './IntelligenceScan';

describe('IntelligenceScan', () => {
  it('renders the heading and every step, regardless of reveal progress', () => {
    // useStagedReveal only changes opacity/transform on each item -- it never removes them from
    // the DOM (same convention DashboardMock's own progressive panels use), so every step is
    // queryable immediately even before the observer fires.
    render(<IntelligenceScan />);
    expect(screen.getByText(heroIntelligence.heading)).toBeInTheDocument();
    heroIntelligence.steps.forEach((step) => {
      expect(screen.getByText(step)).toBeInTheDocument();
    });
  });
});
