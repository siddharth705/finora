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
});
