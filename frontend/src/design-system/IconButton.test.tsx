import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Pencil } from 'lucide-react';
import { forwardRef } from 'react';

// Same approach as Button.test.tsx -- see that file's comment for why useReducedMotion is mocked
// directly and why motion.button is intercepted to capture its actual gesture props.
let motionButtonProps: Record<string, unknown> = {};

vi.mock('framer-motion', async (importOriginal) => {
  const actual = await importOriginal<typeof import('framer-motion')>();
  const React = await import('react');
  return {
    ...actual,
    useReducedMotion: vi.fn(),
    motion: {
      ...actual.motion,
      button: forwardRef((props: any, ref: any) => {
        motionButtonProps = props;
        const { whileTap, whileHover, ...domProps } = props;
        return React.createElement('button', { ref, ...domProps });
      }),
    },
  };
});

import { useReducedMotion } from 'framer-motion';
import { IconButton } from './IconButton';

describe('IconButton', () => {
  afterEach(() => {
    vi.mocked(useReducedMotion).mockReset();
    motionButtonProps = {};
  });

  it('renders the icon and is reachable by its required aria-label', async () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    const onClick = vi.fn();
    render(<IconButton icon={<Pencil />} aria-label="Edit transaction" onClick={onClick} />);

    const button = screen.getByRole('button', { name: 'Edit transaction' });
    await userEvent.click(button);
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it('defaults to type="button"', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    render(<IconButton icon={<Pencil />} aria-label="Edit" />);
    expect(screen.getByRole('button', { name: 'Edit' })).toHaveAttribute('type', 'button');
  });

  it('swaps to a spinner and disables while loading, hiding the icon', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    render(<IconButton icon={<Pencil data-testid="pencil-icon" />} aria-label="Edit" loading />);
    // Name carries the pending state now -- see the accessible-name test below.
    const button = screen.getByRole('button', { name: 'Edit, loading' });

    expect(button).toBeDisabled();
    expect(button.querySelector('.animate-spin')).toBeInTheDocument();
    expect(screen.queryByTestId('pencil-icon')).not.toBeInTheDocument();
  });

  /**
   * Deliberately a label suffix rather than the sr-only span Button uses: `aria-label` REPLACES an
   * element's contents when computing its accessible name, so sr-only text inside this button
   * would never be announced. Regression test for that specific asymmetry between the two
   * components -- the obvious "just do what Button does" fix is silently a no-op here.
   */
  it('puts the pending state in its label, since aria-label replaces element content', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    render(<IconButton icon={<Pencil />} aria-label="Discard import" loading />);

    const button = screen.getByRole('button', { name: 'Discard import, loading' });
    expect(button).toHaveAttribute('aria-busy', 'true');
  });

  it('leaves the label alone when not loading', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    render(<IconButton icon={<Pencil />} aria-label="Discard import" />);

    const button = screen.getByRole('button', { name: 'Discard import' });
    expect(button).toHaveAttribute('aria-busy', 'false');
  });

  it('applies the danger variant as always-red at rest, not muted-until-hover', () => {
    // Matches Button's own danger variant (text-danger unconditional) -- see IconButton.tsx's
    // own comment on this exact regression, caught only once something actually used this variant.
    vi.mocked(useReducedMotion).mockReturnValue(false);
    render(<IconButton icon={<Pencil />} aria-label="Delete" variant="danger" />);
    expect(screen.getByRole('button', { name: 'Delete' })).toHaveClass('text-danger', 'hover:bg-danger-bg');
  });

  it('applies the sm size', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    render(<IconButton icon={<Pencil />} aria-label="Edit" size="sm" />);
    expect(screen.getByRole('button', { name: 'Edit' })).toHaveClass('w-7', 'h-7');
  });

  it('is keyboard-accessible as a real button element, with no custom role/tabIndex needed', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    render(<IconButton icon={<Pencil />} aria-label="Edit" />);
    const button = screen.getByRole('button', { name: 'Edit' });

    expect(button.tagName).toBe('BUTTON');
    expect(button).not.toHaveAttribute('tabindex', '-1');
  });

  it('passes a tap-scale gesture prop when motion is not reduced', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    render(<IconButton icon={<Pencil />} aria-label="Edit" />);
    expect(motionButtonProps.whileTap).toEqual({ scale: 0.9 });
  });

  it('suppresses the tap-scale gesture prop under reduced motion', () => {
    vi.mocked(useReducedMotion).mockReturnValue(true);
    render(<IconButton icon={<Pencil />} aria-label="Edit" />);
    expect(motionButtonProps.whileTap).toBeUndefined();
  });
});
