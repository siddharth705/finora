import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('framer-motion', async (importOriginal) => {
  const actual = await importOriginal<typeof import('framer-motion')>();
  return { ...actual, useReducedMotion: vi.fn() };
});
vi.mock('./useLearningTimeline', () => ({ useLearningTimeline: vi.fn() }));

import { useReducedMotion } from 'framer-motion';
import { useLearningTimeline } from './useLearningTimeline';
import { LearningSection } from './LearningSection';
import { learning } from './landing-config';

describe('LearningSection', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('renders the heading copy and all four learning-loop cards', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    const { container } = render(<LearningSection />);

    // The heading's text is split across a <br/> (title + titleLine2 as separate text nodes), so
    // getByText can't match learning.title alone -- query the heading element directly instead
    // (same pattern ImportSection.test.tsx uses for the same SectionHeading shape).
    const heading = container.querySelector('h2');
    expect(heading?.textContent).toContain(learning.title);
    expect(heading?.textContent).toContain(learning.titleLine2);
    expect(screen.getAllByText('Amazon').length).toBeGreaterThanOrEqual(3);
    expect(screen.getByText('First import')).toBeInTheDocument();
    expect(screen.getByText('You fix it')).toBeInTheDocument();
    expect(screen.getByText('Fynora records it')).toBeInTheDocument();
    expect(screen.getByText('Next import')).toBeInTheDocument();
  });

  it('shows the non-numeric confirmation line, never a fabricated percentage', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    render(<LearningSection />);

    expect(screen.getByText('Pattern confirmed')).toBeInTheDocument();
    expect(screen.queryByText(/%/)).not.toBeInTheDocument();
  });

  it('does not mark the card container aria-hidden -- these cards are real content, not a decorative scene', () => {
    // Per the design spec's Accessibility section: unlike ImportSection's illustration (marked
    // aria-hidden because it's decorative), the container holding these four cards must stay in
    // the normal reading order. This does not extend to lucide-react's own icons, which set
    // aria-hidden="true" on themselves by default -- standard, correct practice for a decorative
    // glyph sitting next to real visible text (the Check icons here always pair with a text tag).
    vi.mocked(useReducedMotion).mockReturnValue(false);
    render(<LearningSection />);
    expect(screen.getByText('First import').closest('[aria-hidden="true"]')).not.toBeInTheDocument();
  });

  it('enables the timeline only when motion is allowed', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    render(<LearningSection />);
    expect(vi.mocked(useLearningTimeline).mock.calls[0][0].enabled).toBe(true);
  });

  it('disables the timeline and still shows all four cards under prefers-reduced-motion', () => {
    vi.mocked(useReducedMotion).mockReturnValue(true);
    render(<LearningSection />);

    expect(vi.mocked(useLearningTimeline).mock.calls[0][0].enabled).toBe(false);
    expect(screen.getByText('First import')).toBeInTheDocument();
    expect(screen.getByText('Next import')).toBeInTheDocument();
    expect(screen.getByText('Pattern confirmed')).toBeInTheDocument();
  });
});
