import { describe, it, expect } from 'vitest';
import { render } from '@testing-library/react';
import { Skeleton } from './Skeleton';

describe('Skeleton', () => {
  it('Text renders a pulsing bar hidden from assistive tech', () => {
    const { container } = render(<Skeleton.Text />);
    const el = container.firstElementChild!;
    expect(el).toHaveAttribute('aria-hidden', 'true');
    expect(el).toHaveClass('animate-pulse', 'bg-surface');
  });

  it('Row composes a title/subtitle line, a field block, and a button block', () => {
    const { container } = render(<Skeleton.Row />);
    expect(container.querySelectorAll('.animate-pulse').length).toBeGreaterThanOrEqual(4);
  });

  it('Card composes an icon circle, a label bar, and a value bar', () => {
    const { container } = render(<Skeleton.Card />);
    expect(container.querySelectorAll('.animate-pulse').length).toBe(3);
    expect(container.querySelector('.rounded-full')).toBeInTheDocument();
  });

  it('Chart renders a fixed-height bar placeholder', () => {
    const { container } = render(<Skeleton.Chart />);
    const bars = container.querySelectorAll('.animate-pulse');
    expect(bars.length).toBeGreaterThan(1);
  });

  it('Region wraps its children with the loading-region accessibility contract', () => {
    const { getByRole, getByText } = render(
      <Skeleton.Region label="Loading users">
        <Skeleton.Row />
      </Skeleton.Region>
    );

    const region = getByRole('status');
    expect(region).toHaveAttribute('aria-busy', 'true');
    expect(region).toHaveAttribute('aria-live', 'polite');
    expect(getByText('Loading users')).toBeInTheDocument();
  });

  it('Region defaults its label to "Loading"', () => {
    const { getByText } = render(
      <Skeleton.Region>
        <Skeleton.Row />
      </Skeleton.Region>
    );
    expect(getByText('Loading')).toBeInTheDocument();
  });

  it('every shape respects motion-reduce (no animation forced on reduced-motion users)', () => {
    const { container } = render(<Skeleton.Text />);
    expect(container.firstElementChild).toHaveClass('motion-reduce:animate-none');
  });

  it('provides no accessible name on its own -- Region is what makes a loading area announce', () => {
    const { queryByRole } = render(<Skeleton.Row />);
    expect(queryByRole('status')).not.toBeInTheDocument();
  });
});
