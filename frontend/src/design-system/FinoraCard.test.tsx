import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { FinoraCard } from './FinoraCard';

describe('FinoraCard', () => {
  it('renders its children inside the shared card shell', () => {
    render(<FinoraCard>hello</FinoraCard>);
    expect(screen.getByText('hello')).toBeInTheDocument();
  });

  it('defaults to medium padding', () => {
    render(<FinoraCard>content</FinoraCard>);
    expect(screen.getByText('content')).toHaveClass('p-5');
  });

  it('applies the requested padding variant', () => {
    render(<FinoraCard padding="lg">content</FinoraCard>);
    expect(screen.getByText('content')).toHaveClass('p-6');
  });

  it('applies no padding class when padding is "none", for cards with their own internal split', () => {
    render(<FinoraCard padding="none">content</FinoraCard>);
    expect(screen.getByText('content').className).not.toMatch(/\bp-\d\b/);
  });

  it('merges caller className alongside the base shell classes', () => {
    render(<FinoraCard className="mb-6 flex">content</FinoraCard>);
    expect(screen.getByText('content')).toHaveClass('mb-6', 'flex', 'rounded-xl2');
  });
});
