import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { IntelligencePanel } from './IntelligencePanel';

describe('IntelligencePanel', () => {
  it('renders as a fully realized mock, not a placeholder -- a figure, categorized rows, and an insights line', () => {
    render(<IntelligencePanel />);
    expect(screen.getByText('Insights ready')).toBeInTheDocument();
    expect(screen.getByText('₹42,350')).toBeInTheDocument();
    expect(screen.getByText('Amazon')).toBeInTheDocument();
    expect(screen.getByText('Shopping')).toBeInTheDocument();
  });
});
