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

  it('applies the "success" tone when requested', () => {
    render(<Badge label="On track" tone="success" />);
    expect(screen.getByText('On track')).toHaveClass('text-success');
  });

  it('applies the "warning" tone when requested', () => {
    render(<Badge label="Almost there" tone="warning" />);
    expect(screen.getByText('Almost there')).toHaveClass('text-warning');
  });

  it('applies the "danger" tone when requested', () => {
    render(<Badge label="Over budget" tone="danger" />);
    expect(screen.getByText('Over budget')).toHaveClass('text-danger');
  });

  it('merges a caller className for one-off spacing, without a wrapper element', () => {
    render(<Badge label="Monthly" className="ml-2" />);
    expect(screen.getByText('Monthly')).toHaveClass('ml-2');
  });
});
