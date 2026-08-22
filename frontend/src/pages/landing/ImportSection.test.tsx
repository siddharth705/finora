import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { importSection } from './landing-config';
import { ImportSection } from './ImportSection';

describe('ImportSection', () => {
  it('renders the real copy outside any aria-hidden wrapper', () => {
    // The heading's text is split across a <br/> (title + titleLine2 as separate text nodes), so
    // getByText can't match "Upload once." alone -- query the heading element directly instead.
    const { container } = render(<ImportSection />);
    const heading = container.querySelector('h2');
    expect(heading?.textContent).toContain(importSection.title);
    expect(heading?.textContent).toContain(importSection.titleLine2);
    expect(heading?.closest('[aria-hidden="true"]')).toBeNull();
    expect(screen.getByText(importSection.blurb)).toBeInTheDocument();
  });

  it('renders the scroll-story scene', () => {
    const { container } = render(<ImportSection />);
    expect(container.querySelector('[aria-hidden="true"]')).toBeInTheDocument();
  });

  it('pins the copy and the scene together, not the scene alone', () => {
    // Regression test for a real production bug: when only the scene was pinned, GSAP's
    // pin-spacer inflated just the scene's grid column, leaving the copy centered at one fixed
    // scroll position inside a now-huge row -- for most of the pinned scroll the headline was
    // off-screen and all that showed was the scene floating in blank space. The fix wraps the
    // whole two-column row in the trigger ref, so this heading and the scene must share a common
    // ancestor that is not the <Section> itself (i.e. the grid row, not the whole section).
    const { container } = render(<ImportSection />);
    const heading = container.querySelector('h2');
    const scene = container.querySelector('[aria-hidden="true"]');
    const gridRow = container.querySelector('.grid');
    expect(gridRow).not.toBeNull();
    expect(gridRow?.contains(heading)).toBe(true);
    expect(gridRow?.contains(scene)).toBe(true);
  });
});
