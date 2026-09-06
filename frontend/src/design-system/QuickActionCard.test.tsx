import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { UploadCloud } from 'lucide-react';
import { forwardRef } from 'react';

// Same technique as Button.test.tsx/IconButton.test.tsx -- see those files' comments for why
// useReducedMotion is mocked directly and why the motion.* wrapper is intercepted to capture its
// actual gesture props rather than relying on window.matchMedia or simulated pointer events.
let capturedProps: Record<string, unknown> = {};

vi.mock('framer-motion', async (importOriginal) => {
  const actual = await importOriginal<typeof import('framer-motion')>();
  const React = await import('react');
  const capture = (Tag: string) =>
    forwardRef((props: any, ref: any) => {
      capturedProps = props;
      const { whileTap, whileHover, ...domProps } = props;
      return React.createElement(Tag, { ref, ...domProps });
    });
  return {
    ...actual,
    useReducedMotion: vi.fn(),
    motion: {
      ...actual.motion,
      button: capture('button'),
      create: (Component: any) =>
        forwardRef((props: any, ref: any) => {
          capturedProps = props;
          const { whileTap, whileHover, ...rest } = props;
          return React.createElement(Component, { ref, ...rest });
        }),
    },
  };
});

import { useReducedMotion } from 'framer-motion';
import { QuickActionCard } from './QuickActionCard';

describe('QuickActionCard', () => {
  afterEach(() => {
    vi.mocked(useReducedMotion).mockReset();
    capturedProps = {};
  });

  it('renders as a link when "to" is given', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    render(<MemoryRouter><QuickActionCard icon={UploadCloud} label="Import Statement" to="/app/import" /></MemoryRouter>);
    expect(screen.getByRole('link', { name: /import statement/i })).toHaveAttribute('href', '/app/import');
  });

  it('renders as a button and calls onClick when "to" is not given', async () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    const onClick = vi.fn();
    const user = userEvent.setup();
    render(<MemoryRouter><QuickActionCard icon={UploadCloud} label="Add Transaction" onClick={onClick} /></MemoryRouter>);

    const button = screen.getByRole('button', { name: /add transaction/i });
    await user.click(button);

    expect(onClick).toHaveBeenCalledOnce();
  });

  it('passes whileTap and whileHover gesture props when motion is not reduced', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    render(<MemoryRouter><QuickActionCard icon={UploadCloud} label="Import Statement" to="/app/import" /></MemoryRouter>);

    expect(capturedProps.whileTap).toEqual({ scale: 0.96 });
    expect(capturedProps.whileHover).toEqual({ scale: 1.02 });
  });

  it('suppresses both gesture props under reduced motion, for the Link variant', () => {
    vi.mocked(useReducedMotion).mockReturnValue(true);
    render(<MemoryRouter><QuickActionCard icon={UploadCloud} label="Import Statement" to="/app/import" /></MemoryRouter>);

    expect(capturedProps.whileTap).toBeUndefined();
    expect(capturedProps.whileHover).toBeUndefined();
  });

  it('suppresses both gesture props under reduced motion, for the button variant', () => {
    vi.mocked(useReducedMotion).mockReturnValue(true);
    render(<MemoryRouter><QuickActionCard icon={UploadCloud} label="Add Transaction" onClick={vi.fn()} /></MemoryRouter>);

    expect(capturedProps.whileTap).toBeUndefined();
    expect(capturedProps.whileHover).toBeUndefined();
  });
});
