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
});
