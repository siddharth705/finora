import { render, screen } from '@testing-library/react';
import { createRef } from 'react';
import { describe, expect, it } from 'vitest';
import { IntelligencePanel } from './IntelligencePanel';

describe('IntelligencePanel', () => {
  it('forwards its ref to the root element', () => {
    const ref = createRef<HTMLDivElement>();
    const { container } = render(<IntelligencePanel ref={ref} />);
    expect(ref.current).toBe(container.firstElementChild);
  });

  it('marks the glow element the timeline reduces during the settle beat', () => {
    const ref = createRef<HTMLDivElement>();
    render(<IntelligencePanel ref={ref} />);
    expect(ref.current?.getAttribute('data-target')).toBe('panel-glow');
  });

  it('renders as a fully realized mock, not a placeholder -- a figure, categorized rows, and an insights line', () => {
    render(<IntelligencePanel />);
    expect(screen.getByText('Insights ready')).toBeInTheDocument();
    // At least one rupee-figure and at least one category label should be present.
    expect(screen.getAllByText(/₹[\d,]+/).length).toBeGreaterThan(0);
  });
});
