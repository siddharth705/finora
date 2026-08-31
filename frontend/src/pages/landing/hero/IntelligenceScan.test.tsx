import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { heroIntelligence } from '../landing-config';
import { IntelligenceScan } from './IntelligenceScan';

describe('IntelligenceScan', () => {
  it('renders the heading and every step, regardless of reveal progress', () => {
    // Every step is always in the DOM (same convention DashboardMock's own progressive panels
    // use) -- reveal progress only ever changes opacity/transform on each item, never presence.
    render(<IntelligenceScan />);
    expect(screen.getByText(heroIntelligence.heading)).toBeInTheDocument();
    heroIntelligence.steps.forEach((step) => {
      expect(screen.getByText(step)).toBeInTheDocument();
    });
  });

  it('defaults to fully revealed when rendered standalone with no step prop', () => {
    const { container } = render(<IntelligenceScan />);
    const dimmed = container.querySelectorAll('li[style*="opacity: 0.25"]');
    expect(dimmed.length).toBe(0);
  });

  it('reveals only as many steps as the step prop says, for AnalysisSequence to drive', () => {
    // Regression test: this must be controllable from outside (AnalysisSequence's own shared
    // counter) rather than running its own independent reveal timer, which is what let the ring
    // finish before the checklist did in the first place.
    const { container } = render(<IntelligenceScan step={2} />);
    const items = container.querySelectorAll('li');
    expect(items).toHaveLength(heroIntelligence.steps.length);
    expect(items[0].getAttribute('style')).toContain('opacity: 1');
    expect(items[1].getAttribute('style')).toContain('opacity: 1');
    expect(items[2].getAttribute('style')).toContain('opacity: 0.25');
  });
});
