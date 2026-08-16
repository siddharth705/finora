import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { Badge } from './Badge';

describe('Badge', () => {
  it('renders the label', () => {
    render(<Badge label="Beta" />);
    expect(screen.getByText('Beta')).toBeInTheDocument();
  });

  it('defaults to the "primary" tone', () => {
    render(<Badge label="Beta" />);
    expect(screen.getByText('Beta')).toHaveClass('text-primary');
  });

  it('applies the "neutral" tone when requested', () => {
    render(<Badge label="Monthly" tone="neutral" />);
    expect(screen.getByText('Monthly')).toHaveClass('text-muted');
  });

  it('merges a caller className for one-off spacing, without a wrapper element', () => {
    render(<Badge label="Monthly" className="ml-2" />);
    expect(screen.getByText('Monthly')).toHaveClass('ml-2');
  });
});
