import { render, screen } from '@testing-library/react';
import { createRef } from 'react';
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

function renderStory() {
  // triggerRef is owned by ImportSection in real usage -- it wraps the whole two-column row, not
  // just this scene, so ScrollTrigger pins copy and scene together. See ImportScrollStory's own
  // note on the production bug (blank-scroll dead zone) this ownership split fixes.
  const triggerRef = createRef<HTMLDivElement>();
  return render(<ImportScrollStory triggerRef={triggerRef} />);
}

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
    renderStory();
    expect(vi.mocked(useImportScrollTimeline).mock.calls[0][0].enabled).toBe(true);
  });

  it('renders only the final IntelligencePanel state under prefers-reduced-motion, with the timeline disabled', () => {
    vi.mocked(useReducedMotion).mockReturnValue(true);
    restore = mockMatchMedia({ '(min-width: 768px)': true, '(pointer: coarse)': false });
    const { container } = renderStory();
    expect(vi.mocked(useImportScrollTimeline).mock.calls[0][0].enabled).toBe(false);
    expect(screen.getByText('Insights ready')).toBeInTheDocument();
    expect(container.querySelector('[aria-hidden="true"]')).toBeInTheDocument();
  });

  it('renders ImportRevealSequence instead of the pinned scene on non-desktop, with the timeline disabled', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    restore = mockMatchMedia({ '(min-width: 768px)': false, '(pointer: coarse)': false });
    const { container } = renderStory();
    expect(vi.mocked(useImportScrollTimeline).mock.calls[0][0].enabled).toBe(false);
    expect(screen.getByText('Insights ready')).toBeInTheDocument();
    expect(container.querySelector('[aria-hidden="true"]')).toBeInTheDocument();
  });

  it('marks the whole scene aria-hidden on desktop with motion allowed', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    restore = mockMatchMedia({ '(min-width: 768px)': true, '(pointer: coarse)': false });
    const { container } = renderStory();
    expect(container.querySelector('[aria-hidden="true"]')).toBeInTheDocument();
  });

  it("uses the externally-owned triggerRef, not the scene's own box, as the pin target", () => {
    // Regression test for the production blank-scroll bug: the scene must NOT attach the
    // trigger ref to its own 420px box -- useImportScrollTimeline must receive exactly the ref
    // this component was handed, which ImportSection points at the whole copy+scene row.
    vi.mocked(useReducedMotion).mockReturnValue(false);
    restore = mockMatchMedia({ '(min-width: 768px)': true, '(pointer: coarse)': false });
    const triggerRef = createRef<HTMLDivElement>();
    render(<ImportScrollStory triggerRef={triggerRef} />);
    expect(vi.mocked(useImportScrollTimeline).mock.calls[0][0].triggerRef).toBe(triggerRef);
  });
});
