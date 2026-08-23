import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { mockMatchMedia } from '../../../test/mockMatchMedia';

vi.mock('framer-motion', async (importOriginal) => {
  const actual = await importOriginal<typeof import('framer-motion')>();
  return { ...actual, useReducedMotion: vi.fn() };
});

import { useReducedMotion } from 'framer-motion';
import { ImportRevealSequence } from './ImportRevealSequence';

describe('ImportRevealSequence', () => {
  let restore: (() => void) | undefined;

  afterEach(() => {
    restore?.();
    restore = undefined;
    vi.clearAllMocks();
  });

  it('shows the opening beat (DocumentStack) before the observer ever fires -- the safe default', () => {
    // Regression test: an earlier version defaulted step 0 to the FINISHED panel, so a real
    // visitor's actual sequence was finished -> documents -> processing -> finished, which reads
    // as "nothing is animating" because the default state already IS the end state. The default
    // must be the opening beat, so there's something to visibly progress from.
    vi.mocked(useReducedMotion).mockReturnValue(false);
    render(<ImportRevealSequence />);
    expect(screen.getByText('PDF')).toBeInTheDocument();
    expect(screen.queryByText('Insights ready')).not.toBeInTheDocument();
  });

  it('shows the final state immediately under prefers-reduced-motion, with no staged reveal', () => {
    vi.mocked(useReducedMotion).mockReturnValue(true);
    render(<ImportRevealSequence />);
    expect(screen.getByText('Insights ready')).toBeInTheDocument();
  });
});
