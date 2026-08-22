import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { mockMatchMedia } from '../../../test/mockMatchMedia';

vi.mock('framer-motion', async (importOriginal) => {
  const actual = await importOriginal<typeof import('framer-motion')>();
  return { ...actual, useReducedMotion: vi.fn() };
});
vi.mock('./useImportScrollTimeline', () => ({ useImportScrollTimeline: vi.fn() }));

import { useReducedMotion } from 'framer-motion';
import { useImportScrollTimeline } from './useImportScrollTimeline';
import { ImportScrollStory } from './ImportScrollStory';

describe('ImportScrollStory', () => {
  let restore: (() => void) | undefined;

  afterEach(() => {
    restore?.();
    restore = undefined;
    vi.clearAllMocks();
  });

  it('renders the pinned scene and enables the timeline on desktop with motion allowed', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    restore = mockMatchMedia({ '(min-width: 768px)': true, '(pointer: coarse)': false });
    render(<ImportScrollStory />);
    expect(vi.mocked(useImportScrollTimeline).mock.calls[0][0].enabled).toBe(true);
  });

  it('renders only the final IntelligencePanel state under prefers-reduced-motion, with the timeline disabled', () => {
    vi.mocked(useReducedMotion).mockReturnValue(true);
    restore = mockMatchMedia({ '(min-width: 768px)': true, '(pointer: coarse)': false });
    const { container } = render(<ImportScrollStory />);
    expect(vi.mocked(useImportScrollTimeline).mock.calls[0][0].enabled).toBe(false);
    expect(screen.getByText('Insights ready')).toBeInTheDocument();
    expect(container.querySelector('[aria-hidden="true"]')).toBeInTheDocument();
  });

  it('renders ImportRevealSequence instead of the pinned scene on non-desktop, with the timeline disabled', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    restore = mockMatchMedia({ '(min-width: 768px)': false, '(pointer: coarse)': false });
    const { container } = render(<ImportScrollStory />);
    expect(vi.mocked(useImportScrollTimeline).mock.calls[0][0].enabled).toBe(false);
    expect(screen.getByText('Insights ready')).toBeInTheDocument();
    expect(container.querySelector('[aria-hidden="true"]')).toBeInTheDocument();
  });

  it('marks the whole scene aria-hidden on desktop with motion allowed', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    restore = mockMatchMedia({ '(min-width: 768px)': true, '(pointer: coarse)': false });
    const { container } = render(<ImportScrollStory />);
    expect(container.querySelector('[aria-hidden="true"]')).toBeInTheDocument();
  });
});
