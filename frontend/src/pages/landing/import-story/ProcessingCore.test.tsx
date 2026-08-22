import { render } from '@testing-library/react';
import { createRef } from 'react';
import { describe, expect, it } from 'vitest';
import { ProcessingCore } from './ProcessingCore';

describe('ProcessingCore', () => {
  it('forwards its ref to the root element', () => {
    const ref = createRef<HTMLDivElement>();
    const { container } = render(<ProcessingCore ref={ref} />);
    expect(ref.current).toBe(container.firstElementChild);
  });

  it('marks the core mark and each pipeline stage with the data-target attributes the timeline queries', () => {
    const ref = createRef<HTMLDivElement>();
    render(<ProcessingCore ref={ref} />);
    expect(ref.current?.querySelector('[data-target="core-mark"]')).toBeInTheDocument();
    expect(ref.current?.querySelector('[data-target="stage-extraction"]')).toBeInTheDocument();
    expect(ref.current?.querySelector('[data-target="stage-categorization"]')).toBeInTheDocument();
    expect(ref.current?.querySelector('[data-target="stage-insights"]')).toBeInTheDocument();
  });

  it('renders text naming the pipeline, not a generic AI label', () => {
    const { getByText } = render(<ProcessingCore />);
    expect(getByText('Extraction')).toBeInTheDocument();
    expect(getByText('Categorization')).toBeInTheDocument();
    expect(getByText('Insights')).toBeInTheDocument();
  });
});
