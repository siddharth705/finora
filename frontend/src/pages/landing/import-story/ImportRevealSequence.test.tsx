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

  it('shows the final (IntelligencePanel) state before the observer ever fires -- the safe default', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    render(<ImportRevealSequence />);
    expect(screen.getByText('Insights ready')).toBeInTheDocument();
  });

  it('shows the final state immediately under prefers-reduced-motion, with no staged reveal', () => {
    vi.mocked(useReducedMotion).mockReturnValue(true);
    render(<ImportRevealSequence />);
    expect(screen.getByText('Insights ready')).toBeInTheDocument();
  });
});
