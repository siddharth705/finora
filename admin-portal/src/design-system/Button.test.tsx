import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { forwardRef } from 'react';

// motionButtonProps captures what Button.tsx actually passes to motion.button on its most recent
// render -- the only reliable way to assert on whileTap/whileHover, since framer-motion gesture
// props aren't DOM-observable at rest (they only produce a visible effect during a real pointer
// gesture, which jsdom can't meaningfully simulate). Mocking useReducedMotion directly (not
// window.matchMedia) is the same convention frontend/'s equivalent test uses -- framer-motion's
// real useReducedMotion reads `matchMedia('(prefers-reduced-motion)')`, not
// `(prefers-reduced-motion: reduce)`, so a matchMedia-based mock would need to get that exact
// string right; mocking the hook's return value tests this component's own gating logic instead.
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
import { Button } from './Button';

describe('Button', () => {
  afterEach(() => {
    vi.mocked(useReducedMotion).mockReset();
    motionButtonProps = {};
  });

  it('renders its children and calls onClick', async () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    const onClick = vi.fn();
    render(<Button onClick={onClick}>Save</Button>);

    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it('defaults to type="button" so it never accidentally submits a form', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    render(<Button>Save</Button>);
    expect(screen.getByRole('button', { name: 'Save' })).toHaveAttribute('type', 'button');
  });

  it('lets a caller opt into type="submit"', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    render(<Button type="submit">Submit</Button>);
    expect(screen.getByRole('button', { name: 'Submit' })).toHaveAttribute('type', 'submit');
  });

  it('defaults to the primary variant', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    render(<Button>Save</Button>);
    expect(screen.getByRole('button', { name: 'Save' })).toHaveClass('bg-primary', 'text-on-primary');
  });

  it('applies the secondary and danger variants when requested', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    const { rerender } = render(<Button variant="secondary">Cancel</Button>);
    expect(screen.getByRole('button', { name: 'Cancel' })).toHaveClass('border-border', 'text-ink');

    rerender(<Button variant="danger">Delete</Button>);
    expect(screen.getByRole('button', { name: 'Delete' })).toHaveClass('text-danger');
  });

  it('shows a spinner and disables the button while loading', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    render(<Button loading>Save</Button>);
    const button = screen.getByRole('button', { name: 'Save' });

    expect(button).toBeDisabled();
    expect(button.querySelector('.animate-spin')).toBeInTheDocument();
  });

  it('stays disabled and spinner-free when explicitly disabled without loading', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    render(<Button disabled>Save</Button>);
    const button = screen.getByRole('button', { name: 'Save' });

    expect(button).toBeDisabled();
    expect(button.querySelector('.animate-spin')).not.toBeInTheDocument();
  });

  it('merges a caller className without dropping the base classes', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    render(<Button className="w-full">Save</Button>);
    expect(screen.getByRole('button', { name: 'Save' })).toHaveClass('w-full', 'bg-primary');
  });

  it('passes whileTap and, with hoverScale, whileHover when motion is not reduced', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    render(<Button hoverScale>Save</Button>);

    expect(motionButtonProps.whileTap).toEqual({ scale: 0.96 });
    expect(motionButtonProps.whileHover).toEqual({ scale: 1.02 });
  });

  it('omits whileHover when hoverScale is not set, even without reduced motion', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    render(<Button>Save</Button>);

    expect(motionButtonProps.whileTap).toEqual({ scale: 0.96 });
    expect(motionButtonProps.whileHover).toBeUndefined();
  });

  it('suppresses both whileTap and whileHover under reduced motion, even with hoverScale set', () => {
    vi.mocked(useReducedMotion).mockReturnValue(true);
    render(<Button hoverScale>Save</Button>);

    expect(motionButtonProps.whileTap).toBeUndefined();
    expect(motionButtonProps.whileHover).toBeUndefined();
  });
});
