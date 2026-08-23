import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { mockMatchMedia } from '../../../test/mockMatchMedia';
import { heroScore } from '../landing-config';
import { AnalysisSequence } from './AnalysisSequence';

describe('AnalysisSequence', () => {
  let restore: (() => void) | undefined;

  afterEach(() => {
    restore?.();
    restore = undefined;
  });

  it('keeps the ring at 0 before the checklist has made any progress (no real IntersectionObserver firing)', () => {
    // jsdom's IntersectionObserver is a defined no-op (see src/test/setup.ts) -- it never fires,
    // so useStagedReveal's step counter never advances past 0 here, the same as a real visitor
    // whose scroll hasn't yet carried this section into view. The ring must reflect that, not
    // show its final value regardless.
    render(<AnalysisSequence />);
    expect(screen.getByText('0')).toBeInTheDocument();
    expect(screen.queryByText(String(heroScore.value))).not.toBeInTheDocument();
  });

  it('shows the final ring value immediately under prefers-reduced-motion, alongside a fully revealed checklist', () => {
    // Regression test for the actual bug reported: under reduced motion (or once the checklist
    // genuinely finishes in a real browser), the ring and the checklist must land in their final
    // state TOGETHER -- never the ring alone.
    restore = mockMatchMedia({ '(prefers-reduced-motion: reduce)': true });
    const { container } = render(<AnalysisSequence />);
    expect(screen.getByText(String(heroScore.value))).toBeInTheDocument();
    expect(container.querySelectorAll('li[style*="opacity: 0.25"]')).toHaveLength(0);
  });
});
